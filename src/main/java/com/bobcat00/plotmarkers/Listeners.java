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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Listener;

import com.google.common.eventbus.Subscribe;
import com.plotsquared.core.events.PlotClaimedNotifyEvent;
import com.plotsquared.core.events.post.PostPlotChangeOwnerEvent;
import com.plotsquared.core.events.post.PostPlotDeleteEvent;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotId;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;

public final class Listeners implements Listener
{
    private PlotMarkers plugin;
    private BlueMapAPI bmAPI;
    
    // Only output markers in these worlds
    private Set<String> worlds;
    
    //private final String icon = "marker_tower_red.png";
    //private String iconUrl; // icon file name with partial path
    
    // BlueMap marker set
    private ConcurrentHashMap<String, MarkerSet> markerSets;
    
    // -------------------------------------------------------------------------
    
    public Listeners(PlotMarkers plugin)
    {
        this.plugin = plugin;
        plugin.psAPI.registerListener(this);
        
        // Get list of worlds from config file
        
        worlds = plugin.config.getWorlds();

        // Complicated BlueMap stuff due to the way they do the API

        BlueMapAPI.onEnable(api ->
        {
            // Ensure we're on the main thread
            Bukkit.getScheduler().runTask(plugin, new Runnable()
            {
                @Override
                public void run()
                {
                    bmAPI = api;
                    markerSets = new ConcurrentHashMap<String, MarkerSet>();
                    
                    // Create a BlueMap marker set for each world
                    for (String world : worlds)
                    {
                        if (api.getMap(world).isPresent())
                        {
                            MarkerSet markerSet = MarkerSet.builder()
                                                           .label("Plots")
                                                           .toggleable(true)
                                                           .defaultHidden(false)
                                                           .build();
                            
                            markerSets.put(world, markerSet);
                            
                            api.getMap(world).get().getMarkerSets().put(world+"-marker-set", markerSet);
                            
                            // Copy icon to asset storage
                            String icon = plugin.config.getCustomIcon(world);
                            if (icon != "")
                            {
                                try
                                {
                                    copyIcon(world, icon);
                                }
                                catch (IOException e)
                                {
                                    plugin.getLogger().warning("IOException copying " + icon + " to " + world + " asset storage.");
                                }
                            }
                        }
                        else
                        {
                            plugin.getLogger().warning("No BlueMap definition for world " + world + ".");
                            plugin.getLogger().warning("You defined a world for PlotMarkers but there is no corresponding map in BlueMap.");
                        }
                    }

                    // Get all the PlotSquared plots
                    Set<Plot> plots = plugin.psAPI.getAllPlots();

                    // Create a marker for each plot
                    for (Plot plot : plots)
                    {
                        createMarker(plot);
                    }
                    
                    for (String world : worlds)
                    {
                        if (api.getMap(world).isPresent())
                        {
                            int numMarkers = api.getMap(world).get().getMarkerSets().get(world+"-marker-set").getMarkers().size();
                            plugin.getLogger().info("Created " + numMarkers + " marker" + (numMarkers == 1 ? " for " : "s for ") + world + ".");
                        }
                    }
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
    
    // Create a marker. This will overwrite any existing marker.
    
    private void createMarker(Plot plot)
    {
        if (!worlds.contains(plot.getWorldName()) ||
            !bmAPI.getMap(plot.getWorldName()).isPresent())
        {
            return;
        }
        // Calculate position and ID
        
        String world = plot.getWorldName();
        
        Location top = plot.getTopAbs();
        Location bottom = plot.getBottomAbs();
        double x = (top.getX() + bottom.getX()) / 2.0;
        double y = 0.0;
        Integer configY = plugin.config.getY(world);
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
        
        Calendar firstPlayedDate = new GregorianCalendar();
        firstPlayedDate.setTimeInMillis(player.getFirstPlayed());
        SimpleDateFormat format = new SimpleDateFormat(plugin.config.getDateFormat());
        String firstPlayed = format.format(firstPlayedDate.getTime());

        Calendar lastPlayedDate = new GregorianCalendar();
        lastPlayedDate.setTimeInMillis(player.getLastPlayed());
        String lastPlayed = format.format(lastPlayedDate.getTime());
        
        POIMarker marker = POIMarker.builder()
                                    .position((x+0.5), y, (z+0.5))
                                    .label(player.getName())
                                    .detail(player.getName() + "<br>" +
                                            idX + ";" + idZ + "<br>" +
                                            firstPlayed + "<br>" +
                                            lastPlayed)
                                    .build();
        
        if (plugin.config.getCustomIcon(world) != "")
        {
            String iconUrl = bmAPI.getMap(world).get().getAssetStorage().getAssetUrl(plugin.config.getCustomIcon(world));
            marker.setIcon(iconUrl, plugin.config.getCustomIconAnchorX(world), plugin.config.getCustomIconAnchorY(world));
        }
        
        bmAPI.getMap(world).get().getMarkerSets().get(world+"-marker-set").getMarkers().put(world + x + z, marker);
    }
    
    // -------------------------------------------------------------------------
    
    // Remove a marker
    
    private void removeMarker(Plot plot)
    {
        if (!worlds.contains(plot.getWorldName()) ||
            !bmAPI.getMap(plot.getWorldName()).isPresent())
        {
            return;
        }
        String world = plot.getWorldName();
        Location top = plot.getTopAbs();
        Location bottom = plot.getBottomAbs();
        double x = (top.getX() + bottom.getX()) / 2.0;
        double z = (top.getZ() + bottom.getZ()) / 2.0;
        
        bmAPI.getMap(world).get().getMarkerSets().get(world+"-marker-set").getMarkers().remove(world + x + z);
    }
    
    // -------------------------------------------------------------------------
    
    // Copy icon to BlueMap asset storage
    
    private void copyIcon(String world, String icon) throws IOException
    {
        File inFile = new File(plugin.getDataFolder(), icon);
        FileInputStream in = new FileInputStream(inFile);
        OutputStream out = bmAPI.getMap(world).get().getAssetStorage().writeAsset(icon);
        
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0)
        {
            out.write(buf, 0, len);
        }
        out.close();
        in.close();
        
        plugin.getLogger().info("Icon " + icon + " copied to " + world + " asset storage.");
    }

}
