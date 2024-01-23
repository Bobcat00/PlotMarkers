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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;
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
import com.plotsquared.core.events.post.PostPlotChangeOwnerEvent;
import com.plotsquared.core.events.post.PostPlotDeleteEvent;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotId;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;

public final class Listeners implements Listener
{
    private PlotMarkers plugin;
    private BlueMapAPI bmAPI;
    
    // Maximum runtime of initial task creating markers
    final long maxTaskTime = 10; // msec
    
    // Only output markers in these worlds
    private Set<String> worldNames;
    
    // BlueMap marker set
    private ConcurrentHashMap<String, MarkerSet> markerSets;
    
    // -------------------------------------------------------------------------
    
    public Listeners(PlotMarkers plugin)
    {
        this.plugin = plugin;
        plugin.psAPI.registerListener(this);
        
        // Complicated BlueMap stuff due to the way they do the API

        BlueMapAPI.onEnable(api ->
        {
            // Ensure we're on the main thread
            Bukkit.getScheduler().runTask(plugin, new Runnable()
            {
                @Override
                public void run()
                {
                    // BlueMap Worlds -> Maps -> MarkerSets - > Markers
                    bmAPI = api;
                    
                    plugin.config.reloadConfig();
                    
                    // Get list of worlds from config file
                    worldNames = plugin.config.getWorlds();
                    
                    markerSets = new ConcurrentHashMap<String, MarkerSet>();
                    
                    // Create a BlueMap marker set for each world in our config
                    for (String worldName : worldNames)
                    {
                        // Get all the maps defined for this world
                        BlueMapWorld world = api.getWorld(worldName).orElse(null);
                        if (world != null)
                        {
                            // Markerset which will be used for all maps in this world
                            MarkerSet markerSet = MarkerSet.builder()
                                                           .label("Plots")
                                                           .toggleable(true)
                                                           .defaultHidden(false)
                                                           .build();
                            
                            // Save for our use
                            markerSets.put(worldName, markerSet);
                            
                            // Save in each map defined for this world
                            for (BlueMapMap map : world.getMaps())
                            {
                                map.getMarkerSets().put("plotmarkers", markerSet);
                                
                                // Copy icon to asset storage
                                String icon = plugin.config.getCustomIcon(worldName);
                                if (icon != "")
                                {
                                    try
                                    {
                                        copyIcon(map, icon);
                                    }
                                    catch (IOException e)
                                    {
                                        plugin.getLogger().warning("IOException copying " + icon + " to " + map.getId() + " asset storage.");
                                    }
                                }
                            }
                        }
                        else
                        {
                            plugin.getLogger().warning("No BlueMap definition for world " + worldName + ".");
                            plugin.getLogger().warning("You defined a world for PlotMarkers but there is no corresponding world in BlueMap.");
                        }
                    }

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
                                plugin.getLogger().info("Created " + numMarkers + " marker" + (numMarkers == 1 ? " for " : "s for ") + worldName + ".");
                            }
                        }
                    }, 0, 1); // delay 0, period 1
                }
            });
        });
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
    public void onPlotDelete(PostPlotDeleteEvent e)
    {
        removeMarker(e.getPlot());
    }
    
    // -------------------------------------------------------------------------
    
    // Player quit or was kicked
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        // Update all this player's markers
        Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        
        // Give the player time to logout
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable()
        {
            @Override
            public void run()
            {
                PlotPlayer<?> plotPlayer = plugin.psAPI.wrapPlayer(uuid);
                Set<Plot> plots = plotPlayer.getPlots();
                for (Plot plot : plots)
                {
                    createMarker(plot);
                }
            }
        }, 4L); // Delay 4 ticks (markers are only updated every 10 seconds anyway)
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
        
        if (plugin.config.getCustomIcon(worldName) != "")
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
    
    // -------------------------------------------------------------------------
    
    // Copy icon to BlueMap asset storage
    
    private void copyIcon(BlueMapMap map, String icon) throws IOException
    {
        File inFile = new File(plugin.getDataFolder(), icon);
        FileInputStream in = new FileInputStream(inFile);
        OutputStream out = map.getAssetStorage().writeAsset(icon);
        
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0)
        {
            out.write(buf, 0, len);
        }
        out.close();
        in.close();
        
        plugin.getLogger().info("Icon " + icon + " copied to " + map.getId() + " asset storage.");
    }

}
