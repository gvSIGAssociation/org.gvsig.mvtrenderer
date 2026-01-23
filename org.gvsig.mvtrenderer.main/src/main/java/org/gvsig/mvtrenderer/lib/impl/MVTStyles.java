/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.gvsig.mvtrenderer.lib.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geotools.api.style.FeatureTypeStyle;
import org.geotools.api.style.Style;
import org.geotools.api.style.StyleFactory;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.mbstyle.MBStyle;
import org.geotools.mbstyle.layer.MBLayer;
import org.geotools.mbstyle.parse.MBObjectStops;
import org.geotools.mbstyle.parse.MBStyleParser;
import org.json.simple.parser.ParseException;

import org.locationtech.jts.geom.Polygon;
import org.geotools.data.simple.SimpleFeatureCollection;

/**
 *
 * @author fdiaz
 */
@SuppressWarnings("UseSpecificCatch")
public class MVTStyles {

    private static final Logger LOGGER = Logger.getLogger(MVTStyles.class.getName());

    // El objeto raíz parseado de la librería gt-mbstyle
    private MBStyle mbStyle;
    
    // URL origen del estilo
    private URL url;

    // Caché: ID de capa de estilo -> Objeto Style de GeoTools
    private Map<String, Style> cachedStyles = new HashMap<>();
    
    /**
     * Descarga el json de estilos y lo parsea con MBStyleParser para comprobar que es correcto.
     * Almacena el resultado en memoria (MBStyle) para su uso posterior.
     * 
     * @param url URL del fichero style.json
     * @throws IOException Si hay error de red o de parseo.
     */
    public void download(URL url) throws IOException {
        this.url = url;
        try {
            String jsonContent = readUrl(url);
            MBStyleParser parser = new MBStyleParser();
            // Parseamos y guardamos el objeto MBStyle.
            this.mbStyle = parser.parse(jsonContent);
            
            // Resolver URL relativa de sprites a absoluta
            String spritePath = (String) this.mbStyle.json.get("sprite");
            if (spritePath != null && !spritePath.startsWith("http")) {
                URL absoluteSpriteUrl = new URL(url, spritePath);
                this.mbStyle.json.put("sprite", absoluteSpriteUrl.toString());
                LOGGER.log(Level.INFO, "Resolved sprite URL to: {0}", absoluteSpriteUrl);
            }
            
            // Resolver URL relativa de glyphs a absoluta
            String glyphsPath = (String) this.mbStyle.json.get("glyphs");
            if (glyphsPath != null && !glyphsPath.startsWith("http")) {
                URL absoluteGlyphsUrl = new URL(url, glyphsPath);
                this.mbStyle.json.put("glyphs", absoluteGlyphsUrl.toString());
                LOGGER.log(Level.INFO, "Resolved glyphs URL to: {0}", absoluteGlyphsUrl);
            }
            
            // Limpiamos cachés anteriores si recargamos
            this.cachedStyles.clear();
            
        } catch (ParseException ex) {
            throw new IOException("Error parsing JSON content from " + url, ex);
        } catch (Exception ex) {
            throw new IOException("Invalid Mapbox Style at " + url, ex);
        }
    }

    /**
     * Construye y devuelve la lista de capas (MVTLayer) listas para ser pintadas,
     * en el orden correcto (Z-order) definido por el estilo Mapbox.
     * 
     * @param dataSource Mapa de capas de datos disponibles (source-layer -> FeatureCollection).
     * @param background Polígono que define la extensión de la tesela (para capas de fondo).
     * @return Lista ordenada de objetos MVTLayer.
     */
    public List<MVTLayer> getLayersToDraw(Map<String, SimpleFeatureCollection> dataSource, Polygon background) {
        if (mbStyle == null) {
            throw new IllegalStateException("Style not loaded. Call download() first.");
        }

        List<MVTLayer> layersToDraw = new ArrayList<>();
        
        // Recorremos las capas en el orden definido en el estilo.
        for (MBLayer layer : mbStyle.layers()) {
            String styleLayerId = layer.getId();
            String sourceLayerName = layer.getSourceLayer();
            Style style = getStyle(styleLayerId);
            
            if (style == null) {
                continue; 
            }

            if (sourceLayerName == null) {
                // Caso Background: No tiene source-layer asociado.
                // Usamos el polígono de fondo.
                layersToDraw.add(new MVTLayer(styleLayerId, background, style));
                
            } else if (dataSource.containsKey(sourceLayerName)) {
                // Caso Capa de Datos: Existe en el estilo y tenemos datos para ella.
                SimpleFeatureCollection features = dataSource.get(sourceLayerName);
                layersToDraw.add(new MVTLayer(styleLayerId, features, style));
            }
            // Si la capa está en el estilo pero no hay datos en 'dataSource', se omite.
        }
        
        return layersToDraw;
    }

    /**
     * Calcula y cachea el Style de geotools asociado al styleLayerId indicado y lo devuelve.
     * Implementación Lazy: solo transforma el estilo si no está en caché.
     * 
     * @param styleLayerId ID de la capa de estilo (Mapbox layer id).
     * @return El objeto Style de GeoTools listo para renderizar, o null si el ID no existe.
     */
    public Style getStyle(String styleLayerId) {
        if (mbStyle == null) {
            throw new IllegalStateException("Style not loaded. Call download() first.");
        }

        // 1. Hit de caché
        if (cachedStyles.containsKey(styleLayerId)) {
            return cachedStyles.get(styleLayerId);
        }

        // 2. Miss de caché: Buscar la capa en MBStyle
        MBLayer layer = mbStyle.layer(styleLayerId);
        if (layer == null) {
            LOGGER.log(Level.WARNING, "Style layer ID ''{0}'' not found in style definition.", styleLayerId);
            return null;
        }

        // 3. Transformación Lazy usando gt-mbstyle
        try {
            // Calculamos escalas para la transformación (Mapbox zoom -> OGC Scale Denominator)
            Double minScale = null;
            Double maxScale = null;
            
            // El zoom máximo define la escala mínima (mayor detalle)
            if (layer.getMaxZoom() != Integer.MAX_VALUE) {
                minScale = MBObjectStops.zoomLevelToScaleDenominator(Math.min(25d, layer.getMaxZoom()));
            }
            
            // El zoom mínimo define la escala máxima (menor detalle)
            if (layer.getMinZoom() != Integer.MIN_VALUE) {
                maxScale = MBObjectStops.zoomLevelToScaleDenominator(Math.max(-25d, layer.getMinZoom()));
            }

            // Realizamos la transformación
            List<FeatureTypeStyle> ftsList = layer.transform(mbStyle, minScale, maxScale);
            
            // Empaquetamos en un Style de OGC
            StyleFactory sf = CommonFactoryFinder.getStyleFactory();
            Style geoToolsStyle = sf.createStyle();
            
            for (FeatureTypeStyle fts : ftsList) {
                geoToolsStyle.featureTypeStyles().add(fts);
            }
            
            // 4. Guardar en caché y retornar
            cachedStyles.put(styleLayerId, geoToolsStyle);
            return geoToolsStyle;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error transforming layer " + styleLayerId, e);
            return null;
        }
    }

    private String readUrl(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
             return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
