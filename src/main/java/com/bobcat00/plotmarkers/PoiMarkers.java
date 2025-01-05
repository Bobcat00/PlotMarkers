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

import com.google.common.eventbus.Subscribe;
import com.plotsquared.core.events.PlotClaimedNotifyEvent;
import com.plotsquared.core.events.PlotDeleteEvent;
import com.plotsquared.core.events.post.PostPlotChangeOwnerEvent;
import com.plotsquared.core.events.post.PostPlotDeleteEvent;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotId;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;

public final class PoiMarkers implements Listener
{
    private PlotMarkers plugin;
    private BlueMapAPI bmAPI;
    
    // Maximum runtime of task creating/updating markers
    final long maxTaskTime = 10; // msec
    
    // Only output markers in these worlds
    private Set<String> worldNames;
    
    // BlueMap marker set
    private ConcurrentHashMap<String, MarkerSet> markerSets;
    
    // Map to handle passing data between events
    private Map<String, List<PlotId>> pendingDelete = Collections.synchronizedMap(new HashMap<String, List<PlotId>>());
    
    // -------------------------------------------------------------------------
    
    public PoiMarkers(PlotMarkers plugin, BlueMapAPI bmAPI, ConcurrentHashMap<String, MarkerSet> markerSets)
    {
        this.plugin = plugin;
        this.bmAPI = bmAPI;
        this.markerSets = markerSets;
        
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.psAPI.registerListener(this);
        
        // Get list of worlds from config file
        worldNames = plugin.config.getWorlds();
        
        // Get all the PlotSquared plots
        final Set<Plot> plots = plugin.psAPI.getAllPlots();
        final Iterator<Plot> plotIterator = plots.iterator();
        
        // Break this up into pieces
        Bukkit.getScheduler().runTaskTimer(plugin, task ->
        {
            long startTime = System.currentTimeMillis();
            
            // Create a marker for each plot
            while (plotIterator.hasNext())
            {
                createMarker(plotIterator.next());
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
                    plugin.getLogger().info("Created " + numMarkers + " POI marker" + (numMarkers == 1 ? " for " : "s for ") + worldName + ".");
                }
            }
        }, 0L, 1L); // delay 0, period 1
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot claim and /plot auto
    
    @Subscribe
    public void onPlotClaimNotify(PlotClaimedNotifyEvent e)
    {
        createMarker(e.getPlot());
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot setowner
    
    @Subscribe
    public void onPlotChangeOwner(PostPlotChangeOwnerEvent e)
    {
        createMarker(e.getPlot());
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot delete
    
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
                    removeMarker(plot);
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
                createMarker(plotIterator.next());
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
    
    // Create a marker. This will overwrite any existing marker.
    
    private void createMarker(Plot plot)
    {
        if (!worldNames.contains(plot.getWorldName()) ||
            !bmAPI.getMap(plot.getWorldName()).isPresent())
        {
            return;
        }
        // Calculate position and ID
        
        String worldName = plot.getWorldName();
        
        Location top = plot.getTopAbs();
        Location bottom = plot.getBottomAbs();
        double x = (top.getX() + bottom.getX()) / 2.0;
        double y = 0.0;
        Integer configY = plugin.config.getY(worldName);
        if (configY == null)
        {
            // Use plot heights
            y = (top.getY() + bottom.getY()) / 2.0;
        }
        else
        {
            // Use value from config
            y = configY;
        }
        double z = (top.getZ() + bottom.getZ()) / 2.0;
        
        PlotId plotId = plot.getId();
        int idX = plotId.getX();
        int idZ = plotId.getY();
        
        // Get owner info
        
        UUID owner = plot.getOwnerAbs();
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
        
        POIMarker marker = POIMarker.builder()
                                    .position((x+0.5), y, (z+0.5))
                                    .label(playerName)
                                    .detail(playerName + "<br>" +
                                            idX + ";" + idZ + "<br>" +
                                            firstPlayed + "<br>" +
                                            lastPlayed)
                                    .build();
        
        if (!plugin.config.getCustomIcon(worldName).isEmpty())
        {
            String iconUrl = bmAPI.getMap(worldName).get().getAssetStorage().getAssetUrl(plugin.config.getCustomIcon(worldName));
            marker.setIcon(iconUrl, plugin.config.getCustomIconAnchorX(worldName), plugin.config.getCustomIconAnchorY(worldName));
        }
        
        MarkerSet markerSet = markerSets.get(worldName);
        markerSet.put(worldName + x + z, marker);
    }
    
    // -------------------------------------------------------------------------
    
    // Remove a marker
    
    private void removeMarker(Plot plot)
    {
        if (!worldNames.contains(plot.getWorldName()) ||
            !bmAPI.getMap(plot.getWorldName()).isPresent())
        {
            return;
        }
        String worldName = plot.getWorldName();
        Location top = plot.getTopAbs();
        Location bottom = plot.getBottomAbs();
        double x = (top.getX() + bottom.getX()) / 2.0;
        double z = (top.getZ() + bottom.getZ()) / 2.0;
        
        MarkerSet markerSet = markerSets.get(worldName);
        markerSet.remove(worldName + x + z);
    }

}
