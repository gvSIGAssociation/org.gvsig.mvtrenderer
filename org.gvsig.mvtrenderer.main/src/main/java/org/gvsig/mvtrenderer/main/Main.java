/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.gvsig.mvtrenderer.main;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import javax.imageio.ImageIO;
import org.geotools.api.style.Style;
import org.gvsig.mvtrenderer.lib.impl.MVTStyles;
import org.gvsig.mvtrenderer.lib.impl.MVTTile;

/**
 *
 * @author fdiaz
 */
public class Main {

  public static void main(String[] args) throws MalformedURLException, IOException {
    URL urlTile;
    URL urlStyles = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/resources/styles/root.json");
    urlTile = new URL("https://gvagis.icv.gva.es/server/rest/services/Hosted/MapabaseBasico/VectorTileServer/tile/14/6233/8166.pbf");
//    urlTile = new File("../tmp/8166.pbf").toURI().toURL();
    MVTStyles mvtStyle = new MVTStyles();
    mvtStyle.download(urlStyles);
    // 2. Cargar Tesela
    System.out.println("Descargando tesela...");
    MVTTile mvtTile = new MVTTile();
    mvtTile.download(urlTile);
    BufferedImage image = mvtTile.render(mvtStyle, 512, 512);
    ImageIO.write(image, "png", new File("../tmp/prueba.png"));
    System.out.println("Style downloaded and parsed successfully.");
  }  
}