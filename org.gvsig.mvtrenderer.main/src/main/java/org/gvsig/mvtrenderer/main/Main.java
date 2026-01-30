/**
 * gvSIG. Desktop Geographic Information System.
 *
 * Copyright (C) 2007-2026 gvSIG Association.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 *
 * For any additional information, do not hesitate to contact us
 * at info AT gvsig.com, or visit our website www.gvsig.com.
 */
package org.gvsig.mvtrenderer.main;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import javax.imageio.ImageIO;
import org.apache.commons.lang3.StringUtils;
import org.gvsig.mvtrenderer.lib.impl.MVTStyles;
import org.gvsig.mvtrenderer.lib.impl.MVTTile;

/**
 *
 * @author fdiaz
 */
public class Main {

  public static void main0(String[] args) throws MalformedURLException, IOException {
    URL urlTile;
    URL urlStyles = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/resources/styles/root.json");
//    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/14/6233/8166.pbf");
//    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/14/4825/6155.pbf");
//    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/0/0/0.pbf");

    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/14/6232/8167.pbf");
    MVTStyles mvtStyle = new MVTStyles();
    mvtStyle.download(urlStyles);
    System.out.println("Descargando tesela...");
    MVTTile mvtTile = new MVTTile();
    mvtTile.download(urlTile);
    BufferedImage image = mvtTile.render(mvtStyle, 512, 512);
    ImageIO.write(image, "png", new File("../tmp/prueba.png"));
    System.out.println("Required fonts: " + StringUtils.join(mvtStyle.getUsedFontNames(), ","));
    System.out.println("Style downloaded and parsed successfully.");
  }

  public static void main(String[] args) throws Exception {
    URL urlStyles = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/resources/styles/root.json");
    MVTStyles mvtStyle = new MVTStyles();
    mvtStyle.download(urlStyles);
    int z = 15;
    for (int y = 12465; y <= 12468; y++) {
      for (int x = 16328; x <= 16337; x++) {
        URL urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/{z}/{y}/{x}.pbf");
        MVTTile mvtTile = new MVTTile();
        mvtTile.setForcedExtent(512);
        mvtTile.debugMode = false;
        mvtTile.download(urlTile, z, y, x);
        BufferedImage image = mvtTile.render(mvtStyle, 512, 512);
        ImageIO.write(image, "png", new File(mvtTile.getFolder() + "tile_" + y + "_" + x + ".png"));
      }
    }
    System.out.println("Required fonts: " + StringUtils.join(mvtStyle.getUsedFontNames(), ","));
  }
}
