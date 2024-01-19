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

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class Commands implements CommandExecutor
{
    private PlotMarkers plugin;
    
    public Commands(PlotMarkers plugin)
    {
        this.plugin = plugin;
    }
    
    // -------------------------------------------------------------------------
    
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args)
    {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload"))
        {
            // initialize() will reload the config file
            plugin.listeners.initialize();
            sender.sendMessage("PlotMarkers reloaded.");
            return true; // Normal return
        }
    return false;
    }
    
    // -------------------------------------------------------------------------
    
}
