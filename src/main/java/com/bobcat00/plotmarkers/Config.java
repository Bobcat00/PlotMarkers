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

import org.bukkit.configuration.ConfigurationSection;

public class Config
{
    private PlotMarkers plugin;
    
    // Constructor
    
    public Config(PlotMarkers plugin)
    {
        this.plugin = plugin;
        
        // Check if config.yml exists
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists())
        {
            // Get list of plot worlds
            String[] worlds = plugin.psAPI.getPlotSquared().getPlotAreaManager().getAllWorlds();
            Arrays.sort(worlds);
            
            // Write default config
            boolean firstWorld = true;
            for (String world : worlds)
            {
                plugin.getConfig().set("worlds." + world + ".override-y", true);
                plugin.getConfig().set("worlds." + world + ".y", 63);
                if (firstWorld)
                {
                    plugin.getConfig().setComments("worlds." + world + ".override-y",
                        Arrays.asList("override-y causes the marker to be placed at the specified y coordinate.",
                                      "Normally set y to one above the ground level of your plots.",
                                      "If set to false, the average height (y value) of each plot will be used."));
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
    
}
