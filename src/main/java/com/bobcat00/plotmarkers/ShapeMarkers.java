// PlotMarkers - Add plot markers to BlueMap map
// Copyright 2023 Bobcat00
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bobcat00.plotmarkers;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector2i;
import com.google.common.eventbus.Subscribe;
import com.plotsquared.core.PlotSquared;
import com.plotsquared.core.configuration.file.YamlConfiguration;
import com.plotsquared.core.events.PlotClaimedNotifyEvent;
import com.plotsquared.core.events.PlotDeleteEvent;
import com.plotsquared.core.events.PlotUnlinkEvent;
import com.plotsquared.core.events.post.PostPlotChangeOwnerEvent;
import com.plotsquared.core.events.post.PostPlotDeleteEvent;
import com.plotsquared.core.events.post.PostPlotMergeEvent;
import com.plotsquared.core.events.post.PostPlotUnlinkEvent;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotId;
import com.technicjelle.BMUtils.Cheese;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;

public final class ShapeMarkers implements Listener
{
    private PlotMarkers plugin;
    private BlueMapAPI bmAPI;
    
    // Maximum runtime of task creating/updating markers
    final long maxTaskTime = 10; // msec
    
    // Only output markers in these worlds
    private Set<String> worldNames;
    
    // BlueMap marker set
    private ConcurrentHashMap<String, MarkerSet> markerSets;
    
    // Size of plots in each world
    private ConcurrentHashMap<String, Vector2d> plotSize = new ConcurrentHashMap<String, Vector2d>();
    
    // Maps to handle passing data between events
    private Map<String, List<PlotId>> pendingUnlink = Collections.synchronizedMap(new HashMap<String, List<PlotId>>());
    private Map<String, List<PlotId>> pendingDelete = Collections.synchronizedMap(new HashMap<String, List<PlotId>>());
    
    // -------------------------------------------------------------------------
    
    public ShapeMarkers(PlotMarkers plugin, BlueMapAPI bmAPI, ConcurrentHashMap<String, MarkerSet> markerSets)
    {
        this.plugin = plugin;
        this.bmAPI = bmAPI;
        this.markerSets = markerSets;
        
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.psAPI.registerListener(this);
        
        // Get list of worlds from config file
        worldNames = plugin.config.getWorlds();
        
        // Calculate the plot size for each world
        for (String worldName : worldNames)
        {
            Vector2d size = getPlotSize(worldName);
            plotSize.put(worldName,  size);
        }
        
        // Get all the PlotSquared plots
        final Set<Plot> plots = plugin.psAPI.getAllPlots();
        final Iterator<Plot> plotIterator = plots.iterator();
        
        // Break this up into pieces
        Bukkit.getScheduler().runTaskTimer(plugin, task ->
        {
            long startTime = System.currentTimeMillis();
            
            // Go through the base plots
            // Seems to always be the upper left plot
            while (plotIterator.hasNext())
            {
                Plot plot = plotIterator.next();
                if (plot.isBasePlot())
                {
                    createShape(plot);
                }
                if (System.currentTimeMillis() - startTime > maxTaskTime)
                {
                    return;
                }
            }
            
            // All done
            task.cancel();
            
            for (String worldName : worldNames)
            {
                MarkerSet markerSet = markerSets.get(worldName);
                if (markerSet != null)
                {
                    int numMarkers = markerSet.getMarkers().size();
                    plugin.getLogger().info("Created " + numMarkers + " shape marker" + (numMarkers == 1 ? " for " : "s for ") + worldName + ".");
                }
            }
        }, 0L, 1L); // delay 0, period 1
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot claim and /plot auto
    
    @Subscribe
    public void onPlotClaimNotify(PlotClaimedNotifyEvent e)
    {
        createShape(e.getPlot().getBasePlot(false));
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot setowner
    
    @Subscribe
    public void onPlotChangeOwner(PostPlotChangeOwnerEvent e)
    {
        createShape(e.getPlot().getBasePlot(false));
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot merge after merge has been completed
    
    @Subscribe
    public void onPostPlotMerge(PostPlotMergeEvent e)
    {
        Plot basePlot = e.getPlot().getBasePlot(false);
        
        // Remove existing shapes from all connected plots
        // Presumably these are the individual plots being merged
        
        Set<Plot> plots = basePlot.getConnectedPlots();
        for (Plot plot : plots)
        {
            removeShape(plot);
        }
        
        // Create new shape for the merged plot
        
        createShape(basePlot);
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot unlink in two stages
    
    @Subscribe
    public void onPlotUnlink(PlotUnlinkEvent e)
    {
        // Add connected plots to pending unlink list
        Plot basePlot = e.getPlot().getBasePlot(false);
        pendingUnlink.put(e.getPlot().getWorldName() + ";" + e.getPlot().getId().getX() + ";" + e.getPlot().getId().getY(),
                          basePlot.getConnectedPlots().stream().map(plot -> plot.getId()).toList());
    }
    
    @Subscribe
    public void onPostPlotUnlink(PostPlotUnlinkEvent e)
    {
        // Create shapes for all plots in the list
        Plot basePlot = e.getPlot();
        String worldName = basePlot.getWorldName();
        List<PlotId> plotIds = pendingUnlink.get(worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
        if (plotIds != null)
        {
            for (PlotId plotId : plotIds)
            {
                Plot plot = Plot.getPlotFromString(null, worldName + ";" + plotId.getX() + ";" + plotId.getY(), false);
                if (plot != null)
                {
                    createShape(plot);
                }
                else
                {
                    plugin.getLogger().warning("No plot found for pending unlink " + worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
                }
            }
            pendingUnlink.remove(worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
        }
        else
        {
            plugin.getLogger().warning("No pending unlink found for " + worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
        }
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot delete in two stages
    
    @Subscribe
    public void onPlotDelete(PlotDeleteEvent e)
    {
        // Add connected plots to pending delete list
        Plot basePlot = e.getPlot().getBasePlot(false);
        pendingDelete.put(e.getPlot().getWorldName() + ";" + e.getPlot().getId().getX() + ";" + e.getPlot().getId().getY(),
                          basePlot.getConnectedPlots().stream().map(plot -> plot.getId()).toList());
    }
    
    @Subscribe
    public void onPostPlotDelete(PostPlotDeleteEvent e)
    {
        // Remove markers for all plots in the list
        
        Plot basePlot = e.getPlot();
        String worldName = basePlot.getWorldName();
        List<PlotId> plotIds = pendingDelete.get(worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
        if (plotIds != null)
        {
            for (PlotId plotId : plotIds)
            {
                Plot plot = Plot.getPlotFromString(null, worldName + ";" + plotId.getX() + ";" + plotId.getY(), false);
                if (plot != null)
                {
                    removeShape(plot);
                }
                else
                {
                    plugin.getLogger().warning("No plot found for pending delete " + worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
                }
            }
            pendingDelete.remove(worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
        }
        else
        {
            plugin.getLogger().warning("No pending delete found for " + worldName + ";" + basePlot.getId().getX() + ";" + basePlot.getId().getY());
        }
    }
    
    // -------------------------------------------------------------------------
    
    // Player quit or was kicked
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        // Update all this player's markers
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PlotPlayer<?> plotPlayer = plugin.psAPI.wrapPlayer(uuid);
        final Set<Plot> plots = plotPlayer.getPlots();
        final Iterator<Plot> plotIterator = plots.iterator();
        
        // Give the player time to logout and update in pieces
        Bukkit.getScheduler().runTaskTimer(plugin, task ->
        {
            long startTime = System.currentTimeMillis();
            
            while (plotIterator.hasNext())
            {
                Plot plot = plotIterator.next();
                if (plot.isBasePlot())
                {
                    createShape(plot);
                }
                if (System.currentTimeMillis() - startTime > maxTaskTime)
                {
                    return;
                }
            }
            
            // All done
            task.cancel();
            
        }, 4L, 1L); // Delay 4 ticks, period 1 tick
    }
    
    // -------------------------------------------------------------------------
    
    // Create a shape. This will overwrite any existing shape.
    // getConnected should normally be true.
    
    private void createShape(Plot basePlot)
    {
        String worldName = basePlot.getWorldName();
        
        if (!worldNames.contains(worldName))
        {
            return;
        }
        
        // Calculate y position
        
        double y = 0.0;
        Integer configY = plugin.config.getY(worldName);
        if (configY == null)
        {
            // Use plot heights
            Location top = basePlot.getTopAbs();
            Location bottom = basePlot.getBottomAbs();
            y = (top.getY() + bottom.getY()) / 2.0;
        }
        else
        {
            // Use value from config
            y = configY;
        }
        
        // Get plot ID
        
        PlotId plotId = basePlot.getId();
        int idX = plotId.getX();
        int idZ = plotId.getY();
        
        // Get owner info
        
        UUID owner = basePlot.getOwnerAbs();
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        
        String playerName = player.getName();
        if (playerName == null)
        {
            // No player name, use UUID instead
            playerName = owner.toString();
        }
        
        Calendar firstPlayedDate = new GregorianCalendar();
        firstPlayedDate.setTimeInMillis(player.getFirstPlayed());
        SimpleDateFormat format = new SimpleDateFormat(plugin.config.getDateFormat());
        String firstPlayed = format.format(firstPlayedDate.getTime());

        Calendar lastPlayedDate = new GregorianCalendar();
        long lastPlayedMillis = player.getLastPlayed();
        if (lastPlayedMillis == 0)
        {
            // New player, use first played date as last played date
            lastPlayedDate = firstPlayedDate;
        }
        else
        {
            lastPlayedDate.setTimeInMillis(lastPlayedMillis);
        }
        String lastPlayed = format.format(lastPlayedDate.getTime());
        
        // Get the plots for this shape
        
        Vector2i[] plotCoordinates = basePlot.getConnectedPlots().stream().
                                     map(plot -> new Vector2i(plot.getId().getX()-1, plot.getId().getY()-1)).toArray(Vector2i[]::new);
        Collection<Cheese> platter = Cheese.createPlatterFromCells(plotSize.get(worldName), plotCoordinates);
        
        for (Cheese cheese : platter)
        {
            ShapeMarker marker = ShapeMarker.builder()
                    .shape(cheese.getShape(), (float)y)
                    .holes(cheese.getHoles().toArray(Shape[]::new))
                    .label(playerName)
                    .detail(playerName + "<br>" +
                            idX + ";" + idZ + "<br>" +
                            firstPlayed + "<br>" +
                            lastPlayed)
                    .depthTestEnabled(false)
                    .lineWidth(plugin.config.getLineWidth(worldName))
                    .fillColor(new Color(plugin.config.getFillColor(worldName), plugin.config.getFillOpacity(worldName)))
                    .lineColor(new Color(plugin.config.getLineColor(worldName), plugin.config.getLineOpacity(worldName)))
                    .build();
            
            MarkerSet markerSet = markerSets.get(worldName);
            markerSet.put("shape" + worldName + idX + idZ, marker);
        }
    }
    
    // -------------------------------------------------------------------------
    
    // Remove a marker
    
    private void removeShape(Plot plot)
    {
        if (!worldNames.contains(plot.getWorldName()) ||
            !bmAPI.getMap(plot.getWorldName()).isPresent())
        {
            return;
        }
        String worldName = plot.getWorldName();
        PlotId plotId = plot.getId();
        int idX = plotId.getX();
        int idZ = plotId.getY();

        MarkerSet markerSet = markerSets.get(worldName);
        markerSet.remove("shape" + worldName + idX + idZ);
    }
    
    // -------------------------------------------------------------------------
    
    // Get the plot size for a world. It doesn't appear possible to read this
    // from the API or to get plots if the the world is empty. So we'll just get
    // it from the PlotSquared world configuration.
    
    private Vector2d getPlotSize(String world)
    {
        PlotSquared plotSquared = PlotSquared.get();
        YamlConfiguration worldConfig = plotSquared.getWorldConfiguration();
        
        int plotSize = worldConfig.getInt("worlds." + world + ".plot.size");
        int roadWidth = worldConfig.getInt("worlds." + world + ".road.width");
        double totalSize = (double)(plotSize + roadWidth);
        
        return Vector2d.from(totalSize, totalSize);
    }
    
}
