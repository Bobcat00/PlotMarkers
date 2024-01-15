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
import com.plotsquared.core.location.Location;
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
                        createMarker(plot);
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
        // Calculate position and ID
        
        Location top = plot.getTopAbs();
        Location bottom = plot.getBottomAbs();
        double x = (top.getX() + bottom.getX()) / 2.0;
        double y = 65.0; //(top.getY() + bottom.getY()) / 2.0;
        double z = (top.getZ() + bottom.getZ()) / 2.0;
        
        PlotId plotId = plot.getId();
        int idX = plotId.getX();
        int idZ = plotId.getY();
        
        // Get owner info
        
        UUID owner = plot.getOwnerAbs();
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        
        Calendar firstPlayedDate = new GregorianCalendar();
        firstPlayedDate.setTimeInMillis(player.getFirstPlayed());
        SimpleDateFormat format1 = new SimpleDateFormat("MM/dd/yy");
        String firstPlayed = format1.format(firstPlayedDate.getTime());

        Calendar lastPlayedDate = new GregorianCalendar();
        lastPlayedDate.setTimeInMillis(player.getLastPlayed());
        String lastPlayed = format1.format(lastPlayedDate.getTime());
        
        POIMarker marker = POIMarker.builder()
                                    .position((x+0.5), y, (z+0.5))
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
    
    private void removeMarker(Plot plot)
    {
        Location top = plot.getTopAbs();
        Location bottom = plot.getBottomAbs();
        double x = (top.getX() + bottom.getX()) / 2.0;
        double z = (top.getZ() + bottom.getZ()) / 2.0;
        
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
