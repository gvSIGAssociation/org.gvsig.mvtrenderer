/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.gvsig.mvtrenderer.lib.impl;

import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.MvtReader;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.TagKeyValueMapConverter;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsLayer;
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsMvt;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.FeatureType;
import org.geotools.api.feature.type.Name;
import org.geotools.api.feature.type.PropertyDescriptor;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.PropertyName;
import org.geotools.api.style.Rule;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.renderer.style.StyleAttributeExtractor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;

/**
 *
 * @author fdiaz
 */
public class MVTTile {

    private Map<String, SimpleFeatureCollection> sourceLayers = new HashMap<>();
    private Polygon tileExtent; 
    private double widthInMapUnits = 4096;
    private double heightInMapUnits = 4096;
    private static final Logger LOGGER = Logger.getLogger(MVTTile.class.getName());

    public void download(URL url) throws IOException {
        System.out.println("MVTTile downloading from: " + url);
        
        // 1. Descargar a fichero temporal (gestionando GZIP)
        File tempFile = File.createTempFile("mvt_tile_", ".pbf");
        tempFile.deleteOnExit(); 
        
        try (InputStream is = url.openStream();
             PushbackInputStream pbIs = new PushbackInputStream(is, 2)) {
            
            // Comprobar "Magic Numbers" de GZIP (0x1f, 0x8b)
            byte[] signature = new byte[2];
            int len = pbIs.read(signature);
            pbIs.unread(signature, 0, len); // Devolver bytes al stream
            
            InputStream finalIs = pbIs;
            if (len == 2 && signature[0] == (byte) 0x1f && signature[1] == (byte) 0x8b) {
                System.out.println("GZIP compression detected. Decompressing on the fly...");
                finalIs = new GZIPInputStream(pbIs);
            }

//            Files.copy(finalIs, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
//            System.out.println("Downloaded/Decompressed " + tempFile.length() + " bytes to: " + tempFile.getAbsolutePath());
            // 2. Cargar desde fichero usando MvtReader
            JtsMvt mvt = MvtReader.loadMvt(finalIs, new GeometryFactory(), new TagKeyValueMapConverter());

            sourceLayers.clear();

            // Apply transformation to flip Y axis (compensating GeoTools Y-Up assumption vs MVT Y-Down data)
            AffineTransformation t = new AffineTransformation();
            t.scale(1, -1);
            t.translate(0, heightInMapUnits);

            for (JtsLayer layer : mvt.getLayers()) {
                SimpleFeatureCollection collection = convertToFeatureCollection(layer, t);
                sourceLayers.put(layer.getName(), collection);
            }
        }

        // Tile extent default 4096 (standard MVT)
        GeometryFactory gf = new GeometryFactory();
        tileExtent = gf.createPolygon(new Coordinate[]{
            new Coordinate(0, 0),
            new Coordinate(widthInMapUnits, 0),
            new Coordinate(widthInMapUnits, heightInMapUnits),
            new Coordinate(0, heightInMapUnits),
            new Coordinate(0, 0)
        });
    }
    
    public BufferedImage render(MVTStyles mvtStyle, int widthInPixels, int heightInPixels) {
        BufferedImage image = new BufferedImage(widthInPixels, heightInPixels, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        
//        // Apply transformation to flip Y axis (compensating GeoTools Y-Up assumption vs MVT Y-Down data)
//        AffineTransform t = new AffineTransform();
//        t.scale(1, -1);
//        t.translate(0, -heightInPixels);
//        g2.transform(t);

        try {
            Rectangle drawingArea = new Rectangle(0, 0, widthInPixels, heightInPixels);

            List<MVTLayer> layersToDraw = mvtStyle.getLayersToDraw(sourceLayers, tileExtent);

            for (MVTLayer layer : layersToDraw) {
                layer.render(g2, drawingArea, ReferencedEnvelope.reference(tileExtent.getEnvelopeInternal()));
            }
        } finally {
            g2.dispose();
        }
        
        return image;
    }

    private SimpleFeatureCollection convertToFeatureCollection(JtsLayer layer, AffineTransformation t) {
        // 1. Recolectar todos los nombres de atributos posibles en esta capa
        Set<String> attributeNames = new HashSet<>();
        for (Geometry geom : layer.getGeometries()) {
            Object userData = geom.getUserData();
            if (userData instanceof Map) {
                attributeNames.addAll(((Map<String, Object>) userData).keySet());
            }
        }

        // 2. Construir el FeatureType
        SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
        tb.setName(layer.getName());
        tb.add("geometry", Geometry.class); // GeoTools maneja polimorfismo geométrico
        
        for (String attr : attributeNames) {
            // Asumimos String por defecto para simplificar, o Object.
            // GeoTools es estricto con los tipos. Object suele funcionar para evaluación laxa.
            tb.add(attr, Object.class);
        }
        
        SimpleFeatureType type = tb.buildFeatureType();

        // 3. Crear features
        List<SimpleFeature> features = new ArrayList<>();
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(type);

        for (Geometry geom : layer.getGeometries()) {
            geom = t.transform(geom);
            fb.set("geometry", geom);
            
            Object userData = geom.getUserData();
            if (userData instanceof Map) {
                Map<String, Object> attributes = (Map<String, Object>) userData;
                for (String attr : attributeNames) {
                    Object val = attributes.get(attr);
                    if (val != null) {
                        fb.set(attr, val);
                    }
                }
            }
            features.add(fb.buildFeature(null));
        }

        return new ListFeatureCollection(type, features);
    }
    
}