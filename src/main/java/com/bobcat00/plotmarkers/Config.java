// PlotMarkers - Add plot markers to BlueMap map
// Copyright 2024 Bobcat00
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
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.bukkit.configuration.ConfigurationSection;

import com.plotsquared.core.plot.PlotArea;

public class Config
{
    private PlotMarkers plugin;
    
    private boolean defaultOverrideY = true;
    private int defaultY = 63;
    
    // Constructor
    
    public Config(PlotMarkers plugin)
    {
        this.plugin = plugin;
        
        // Check if config.yml exists
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists())
        {
            // Get list of plot worlds
            PlotArea[] plotAreas = plugin.psAPI.getPlotSquared().getPlotAreaManager().getAllPlotAreas();
            Set<String> worlds = new TreeSet<String>();
            for (PlotArea area : plotAreas)
            {
                worlds.add(area.getWorldName());
            }
            
            // Write default config
            
            plugin.getConfig().set("date-format", "MM/dd/yy");
            plugin.getConfig().setComments("date-format",
                Arrays.asList("The date format in the icon pop-ups. See Java's SimpleDateFormat.",
                              "Examples include MM/dd/yy, dd/MM/yy, and yy/MM/dd."));
            
            // Write world configs
            boolean firstWorld = true;
            for (String world : worlds)
            {
                plugin.getConfig().set("worlds." + world + ".override-y", defaultOverrideY);
                plugin.getConfig().set("worlds." + world + ".y", defaultY);
                plugin.getConfig().set("worlds." + world + ".custom-icon", "");
                plugin.getConfig().set("worlds." + world + ".custom-icon-anchor-x", 0);
                plugin.getConfig().set("worlds." + world + ".custom-icon-anchor-y", 0);
                
                plugin.getConfig().setComments("worlds",
                        Arrays.asList("Markers will be created for each world listed below."));
                if (firstWorld)
                {
                    plugin.getConfig().setComments("worlds." + world + ".override-y",
                        Arrays.asList("override-y causes the marker to be placed at the specified y coordinate.",
                                      "Normally leave this true and set y to one above the ground level of your plots.",
                                      "If set to false, the average height (y value) of each plot will be used."));
                    plugin.getConfig().setComments("worlds." + world + ".custom-icon",
                        Arrays.asList("Specify a custom icon in the plugin folder if you don't want the default icon.",
                                      "The anchor is which pixel on the marker-image will be placed at the marker's position."));
                    firstWorld = false;
                }
            }
            plugin.saveConfig();
        }
    }
    
    // -------------------------------------------------------------------------
    
    // Get list of worlds from config file
    
    public Set<String> getWorlds()
    {
        ConfigurationSection confSect = plugin.getConfig().getConfigurationSection("worlds");
        if (confSect != null)
        {
            return confSect.getKeys(false);
        }
        return null;
    }
    
    // -------------------------------------------------------------------------
    
    // Get y coordinate for this world. This returns either the defined value or
    // null, depending on override-y.
    
    public Integer getY(String world)
    {
        if (plugin.getConfig().getBoolean("worlds." + world + ".override-y", defaultOverrideY))
        {
            return plugin.getConfig().getInt("worlds." + world + ".y", defaultY);
        }
        else
        {
            return null;
        }
    }
    
    // -------------------------------------------------------------------------
    
    // Get the custom icon string for this world. May be an empty String.
    
    public String getCustomIcon(String world)
    {
        return plugin.getConfig().getString("worlds." + world + ".custom-icon", "");
    }
    
    // -------------------------------------------------------------------------
    
    // Get the custom icon string for this world. May be an empty String.
    
    public int getCustomIconAnchorX(String world)
    {
        return plugin.getConfig().getInt("worlds." + world + ".custom-icon-anchor-x", 0);
    }
    
    // -------------------------------------------------------------------------
    
    // Get the custom icon string for this world. May be an empty String.
    
    public int getCustomIconAnchorY(String world)
    {
        return plugin.getConfig().getInt("worlds." + world + ".custom-icon-anchor-y", 0);
    }
    
    // -------------------------------------------------------------------------
    
    // Get the date format
    
    public String getDateFormat()
    {
        return plugin.getConfig().getString("date-format", "MM/dd/yy");
    }
    
    // -------------------------------------------------------------------------
    
    // Reload config file
    
    public void reloadConfig()
    {
        plugin.reloadConfig();
    }

}
