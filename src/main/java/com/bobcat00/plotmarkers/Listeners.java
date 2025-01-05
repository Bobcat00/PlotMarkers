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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;

public final class Listeners implements Listener
{
    private PlotMarkers  plugin;
    private BlueMapAPI   bmAPI;
    private PoiMarkers   poiMarkers;
    private ShapeMarkers shapeMarkers;
    
    // -------------------------------------------------------------------------
    
    public Listeners(PlotMarkers plugin)
    {
        this.plugin = plugin;
        
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
                    ConcurrentHashMap<String, MarkerSet> poiMarkerSets = new ConcurrentHashMap<String, MarkerSet>();
                    ConcurrentHashMap<String, MarkerSet> shapeMarkerSets = new ConcurrentHashMap<String, MarkerSet>();
                    
                    plugin.config.reloadConfig();
                    
                    // Get list of worlds from config file
                    Set<String> worldNames = plugin.config.getWorlds();
                    
                    // Create BlueMap marker sets for each world in our config
                    for (String worldName : worldNames)
                    {
                        // Get all the maps defined for this world
                        BlueMapWorld world = api.getWorld(worldName).orElse(null);
                        if (world != null)
                        {
                            // Markersets which will be used for all maps in this world
                            MarkerSet poiMarkerSet = MarkerSet.builder()
                                                           .label("Plots")
                                                           .toggleable(true)
                                                           .defaultHidden(false)
                                                           .sorting(0)
                                                           .build();
                            
                            MarkerSet shapeMarkerSet = MarkerSet.builder()
                                                           .label("Shapes")
                                                           .toggleable(true)
                                                           .defaultHidden(false)
                                                           .sorting(1)
                                                           .build();
     
                            // Save for our use
                            poiMarkerSets.put(worldName, poiMarkerSet);
                            shapeMarkerSets.put(worldName, shapeMarkerSet);
                            
                            // Save in each map defined for this world
                            for (BlueMapMap map : world.getMaps())
                            {
                                map.getMarkerSets().put("poimarkers", poiMarkerSet);
                                map.getMarkerSets().put("shapemarkers", shapeMarkerSet);
                                
                                // Copy icon to asset storage
                                String icon = plugin.config.getCustomIcon(worldName);
                                if (!icon.isEmpty())
                                {
                                    try
                                    {
                                        copyIcon(map, icon);
                                    }
                                    catch (IOException e)
                                    {
                                        plugin.getLogger().log(Level.WARNING, "IOException copying " + icon + " to " + map.getId() + " asset storage.", e);
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
                    
                    poiMarkers = new PoiMarkers(plugin, bmAPI, poiMarkerSets);
                    shapeMarkers = new ShapeMarkers(plugin, bmAPI, shapeMarkerSets);

                }
            });
        });
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
