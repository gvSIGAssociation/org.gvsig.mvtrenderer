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
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.commons.lang3.StringUtils;
import org.geotools.mbstyle.MBStyle;
import org.geotools.referencing.CRS;
import org.gvsig.mvtrenderer.lib.impl.MVTStyles;
import org.gvsig.mvtrenderer.lib.impl.MVTTile;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.locationtech.jts.geom.Envelope;

/**
 *
 * @author fdiaz
 */
public class Main {

//  public static void main0(String[] args) throws MalformedURLException, IOException {
//    URL urlTile;
//    URL urlStyles = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/resources/styles/root.json");
////    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/14/6233/8166.pbf");
////    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/14/4825/6155.pbf");
////    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/0/0/0.pbf");
//
//    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/14/6232/8167.pbf");
//    MVTStyles mvtStyle = new MVTStyles();
//    mvtStyle.download(urlStyles);
//    System.out.println("Descargando tesela...");
//    MVTTile mvtTile = new MVTTile();
//    mvtTile.download(urlTile);
//    BufferedImage image = mvtTile.render(mvtStyle, 512, 512);
//    ImageIO.write(image, "png", new File("../tmp/prueba.png"));
//    System.out.println("Required fonts: " + StringUtils.join(mvtStyle.getUsedFontNames(), ","));
//    System.out.println("Style downloaded and parsed successfully.");
//  }

  /**
   * Calcula el Envelope de un tile de OSM en coordenadas Web Mercator
   * (EPSG:3857). Las unidades resultantes están en metros.
   *
   * @param x Coordenada X del tile
   * @param y Coordenada Y del tile
   * @param z Nivel de zoom
   * @return Envelope en metros [minX, maxX, minY, maxY]
   */
  private static Envelope getOSMTileEnvelope(int x, int y, int z) {
    // La circunferencia de la Tierra en el Ecuador para EPSG:3857
    // Corresponde a 2 * PI * 6378137 metros
    double worldSize = 40075016.68557849;
    double originShift = worldSize / 2.0; // 20037508.34...

    // Tamaño de una tesela en metros para el nivel de zoom actual
    double numTiles = Math.pow(2, z);
    double tileSizeMeters = worldSize / numTiles;

    // Cálculo de las coordenadas X (Oeste a Este)
    // El origen X (0) en metros está en el meridiano de Greenwich. 
    // Los tiles empiezan en el borde izquierdo (-originShift)
    double minX = -originShift + (x * tileSizeMeters);
    double maxX = -originShift + ((x + 1) * tileSizeMeters);

    // Cálculo de las coordenadas Y (Sur a Norte)
    // En el sistema de tiles de OSM, y=0 es el Norte.
    // En EPSG:3857, el eje Y positivo va hacia el Norte.
    double maxY = originShift - (y * tileSizeMeters);
    double minY = originShift - ((y + 1) * tileSizeMeters);

    return new Envelope(minX, maxX, minY, maxY);
  }

  public static void main(String[] args) throws Exception {
    URL urlStyles = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/resources/styles/root.json");
    MVTStyles mvtStyle = new MVTStyles();
    mvtStyle.download(urlStyles);
    int z = 15;
    for (int y = 12465; y <= 12468; y++) {
      for (int x = 16328; x <= 16337; x++) {
        URL urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/{z}/{y}/{x}.pbf");
//        URL urlTile = URI.create("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/{z}/{y}/{x}.pbf").toURL();
        MVTTile mvtTile = new MVTTile(); //CRS.decode("EPSG:3857"),CRS.decode("EPSG:3857"));
        mvtTile.debugMode = false;
        mvtTile.download(urlTile, z, y, x, getOSMTileEnvelope(x, y, z), mvtStyle.extractFieldsFromStyles());
        BufferedImage image = mvtTile.render(mvtStyle, 512, 512);
        ImageIO.write(image, "png", new File(mvtTile.getFolder() + "tile_" + y + "_" + x + ".png"));
      }
    }
    System.out.println("Required fonts: " + StringUtils.join(mvtStyle.getUsedFontNames(), ","));
  }
  
}
