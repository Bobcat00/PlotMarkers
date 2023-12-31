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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Listener;

import com.google.common.eventbus.Subscribe;
import com.plotsquared.core.PlotAPI;
import com.plotsquared.core.events.PlotClaimedNotifyEvent;
import com.plotsquared.core.events.post.PostPlotChangeOwnerEvent;
import com.plotsquared.core.events.post.PostPlotDeleteEvent;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotId;

import de.bluecolored.bluemap.api.AssetStorage;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;

public final class Listeners implements Listener
{
    private PlotMarkers plugin;
    
    // Only output messages in this world
    private final String plotworld = "plotworld";
    
    private final String icon = "marker_tower_red.png";
    
    private String iconUrl; // icon file name with partial path
    
    private PlotAPI psAPI;
    
    // BlueMap marker set
    private MarkerSet markerSet;
    
    // -------------------------------------------------------------------------
    
    public Listeners(PlotMarkers plugin)
    {
        this.plugin = plugin;
        this.psAPI = new PlotAPI();
        this.psAPI.registerListener(this);

        // Complicated BlueMap stuff due to the way they do the API

        BlueMapAPI.onEnable(api ->
        {
            // Ensure we're on the main thread
            Bukkit.getScheduler().runTask(plugin, new Runnable()
            {
                @Override
                public void run()
                {
                    iconUrl = api.getMap(plotworld).get().getAssetStorage().getAssetUrl(icon);
                    
                    // Copy icon to asset storage
                    try
                    {
                        copyIcon(api);
                    }
                    catch (IOException e)
                    {
                        plugin.getLogger().warning("IOException copying icon to asset storage.");
                    }
                    
                    // Create BlueMap marker set
                    markerSet = MarkerSet.builder()
                                         .label("Plots")
                                         .toggleable(true)
                                         .defaultHidden(false)
                                         .build();

                    api.getMap(plotworld).get().getMarkerSets().put("plot-marker-set", markerSet);

                    // Get all the PlotSquared plots
                    Set<Plot> plots = psAPI.getAllPlots();

                    // Create a marker for each plot
                    int numMarkers = 0;
                    for (Plot plot : plots)
                    {
                        PlotId plotId = plot.getId();
                        createMarker(plotId.getX(), plotId.getY(), plot.getOwnerAbs());
                        ++numMarkers;
                    }

                    plugin.getLogger().info("Created " + numMarkers + " markers.");
                }
            });
        });
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot claim and /plot auto
    
    @Subscribe
    public void onPlotClaimNotify(PlotClaimedNotifyEvent e)
    {
        Plot plot = e.getPlot();
        PlotId plotId = plot.getId();
        UUID owner = plot.getOwnerAbs();
        createMarker(plotId.getX(), plotId.getY(), owner);
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot setowner
    
    @Subscribe
    public void onPlotChangeOwner(PostPlotChangeOwnerEvent e)
    {
        Plot plot = e.getPlot();
        PlotId plotId = plot.getId();
        UUID owner = plot.getOwnerAbs();
        createMarker(plotId.getX(), plotId.getY(), owner);
    }
    
    // -------------------------------------------------------------------------
    
    // Handle /plot delete
    
    @Subscribe
    public void onPlotDelete(PostPlotDeleteEvent e)
    {
        PlotId plotId = e.getPlot().getId();
        removeMarker(plotId.getX(), plotId.getY());
    }
    
    // -------------------------------------------------------------------------
    
    // Create a marker. This will overwrite any existing marker.
    
    private void createMarker(int idX, int idZ, UUID owner)
    {
        // plugin.getLogger().info("Creating marker " + idX + ", " + idZ + ", " + owner.toString());
        
        int x = 80 * idX - 40;
        int z = 80 * idZ - 40;
        
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        
        Calendar firstPlayedDate = new GregorianCalendar();
        firstPlayedDate.setTimeInMillis(player.getFirstPlayed());
        SimpleDateFormat format1 = new SimpleDateFormat("MM/dd/yy");
        String firstPlayed = format1.format(firstPlayedDate.getTime());

        Calendar lastPlayedDate = new GregorianCalendar();
        lastPlayedDate.setTimeInMillis(player.getLastPlayed());
        String lastPlayed = format1.format(lastPlayedDate.getTime());
        
        POIMarker marker = POIMarker.builder()
                                    .position((x+0.5), 65.0, (z+0.5))
                                    .label(player.getName())
                                    .detail(player.getName() + "<br>" +
                                            idX + ";" + idZ + "<br>" +
                                            firstPlayed + "<br>" +
                                            lastPlayed)
                                    .icon(iconUrl, 15, 33)
                                    .build();
        
        markerSet.getMarkers().put(plotworld + x + z, marker);
    }
    
    // -------------------------------------------------------------------------
    
    // Remove a marker
    
    private void removeMarker(int idX, int idZ)
    {
        // plugin.getLogger().info("Removing marker " + idX + ", " + idZ);
        
        int x = 80 * idX - 40;
        int z = 80 * idZ - 40;
        
        markerSet.getMarkers().remove(plotworld + x + z);
    }
    
    // -------------------------------------------------------------------------
    
    // Copy icon to BlueMap asset storage
    
    private void copyIcon(BlueMapAPI api) throws IOException
    {
        AssetStorage assetStorage = api.getMap(plotworld).get().getAssetStorage();
        
        // See if the icon is already there
        if (assetStorage.assetExists(icon))
        {
            return;
        }
        
        // Copy icon
        InputStream in = plugin.getResource(icon);
        OutputStream out = assetStorage.writeAsset(icon);
        
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0)
        {
            out.write(buf, 0, len);
        }
        out.close();
        in.close();
        
        plugin.getLogger().info("Icon copied to map asset storage.");
    }

}
