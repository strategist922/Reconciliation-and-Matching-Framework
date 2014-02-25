package org.kew.stringmod.dedupl.lucene;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexableField;
import org.kew.stringmod.dedupl.configuration.Configuration;
import org.kew.stringmod.dedupl.configuration.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A helper class to
 * --> map from Maps to Lucene Documents and vice versa
 * --> build a query string that lucene understands
 * --> check whether two strings match according to the configured
 *     {@link org.kew.stringmod.dedupl.matchers.Matcher}
 */
public class LuceneUtils {

    private static Logger logger = LoggerFactory.getLogger(LuceneUtils.class);

    public static String doc2String(Document doc){
        return doc2String(doc, "");
    }

    public static Map<String,String> doc2Map(Document doc){
        Map<String,String> map = new HashMap<String, String>();
        for (IndexableField f : doc.getFields()){
            map.put(f.name(), f.stringValue());
        }
        return map;
    }

    public static Document map2Doc(Map<String, String> map) {
        Document doc = new Document();
        for (String key:map.keySet()) {
            String value = map.get(key);
            value = (value != null) ? value: "";
            doc.add(new Field(key, value, Field.Store.YES, Field.Index.ANALYZED));
        }
        return doc;
    }

    public static String doc2String(Document doc, String prefix){
        StringBuffer sb = new StringBuffer();
        for (IndexableField f : doc.getFields()){
            sb.append(prefix)
                .append(f.name()).append(" : " ).append(doc.getField(f.name()).stringValue())
                .append("\n");
        }
        return sb.toString();
    }

    public static String doc2Line(Document doc, String fieldSeparator){
        StringBuffer sb = new StringBuffer();
        for (IndexableField f : doc.getFields()){
            if (sb.length() > 0)
                sb.append(fieldSeparator);
            sb.append(doc.getField(f.name()).stringValue());
        }
        return sb.toString();
    }

    public static String buildQuery(List<Property> properties, Document doc, boolean dedupl){
        Map<String,String> map = doc2Map(doc);
        return buildQuery(properties, map, dedupl);
    }

    public static String buildQuery(List<Property> properties, Map<String,String> map, boolean dedupl){
        StringBuffer sb = new StringBuffer();
        if (dedupl){
            // Be sure not to return self:
            sb.append("NOT " + Configuration.ID_FIELD_NAME + ":" + map.get(Configuration.ID_FIELD_NAME));
        }
        for (Property p : properties){
            if (p.isUseInSelect() || p.isUseInNegativeSelect()) {
                String lookupName = p.getLookupColumnName() + Configuration.TRANSFORMED_SUFFIX;
                String value = map.get(p.getSourceColumnName() + Configuration.TRANSFORMED_SUFFIX);
                // super-csv treats blank as null, we don't for now
                value = (value != null) ? value: "";
                String quotedValue = "\"" + value + "\"";
                if (p.isUseInSelect()){
                    if (StringUtils.isNotBlank(value)){
                        if(p.getMatcher().isExact()){
                            if (sb.length() > 0) sb.append(" AND ");
                            sb.append(lookupName + ":" + quotedValue);
                        }
                        if (p.isIndexLength()){
                            int low = Math.max(0, value.length()-2);
                            int high = value.length()+2;
                            if (sb.length() > 0) sb.append(" AND ");
                            sb.append(" ").append(lookupName + Configuration.LENGTH_SUFFIX + ":[").append(String.format("%02d", low)).append(" TO ").append(String.format("%02d", high)).append("]");
                        }
                        if (p.isIndexInitial()){
                            if (sb.length() > 0) sb.append(" AND ");
                            sb.append(lookupName + Configuration.INITIAL_SUFFIX).append(":").append(quotedValue.substring(0, 2) + "\"");
                        }
                        if (p.isUseWildcard()){
                            if (sb.length() > 0) sb.append(" AND ");
                            sb.append(lookupName).append(":").append(quotedValue.subSequence(0, quotedValue.length()-1)).append("~0.5\"");
                        }
                    }
                }
                else {
                    if (StringUtils.isNotBlank(value)){
                        if (sb.length() > 0) sb.append(" AND ");
                            sb.append(" NOT " + lookupName + ":" + quotedValue);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static boolean recordsMatch(Document from, Document to, List<Property> properties) throws Exception{
        Map<String,String> map = doc2Map(from);
        return recordsMatch(map, to, properties);
    }

    public static boolean recordsMatch(Map<String,String> from, Document to, List<Property> properties) throws Exception{
        boolean recordMatch = false;
        logger.debug("Comparing records: " + from.get(Configuration.ID_FIELD_NAME) + " " + to.get(Configuration.ID_FIELD_NAME));
        for (Property p : properties){
            String sourceName = p.getSourceColumnName() + Configuration.TRANSFORMED_SUFFIX;
            String lookupName = p.getLookupColumnName() + Configuration.TRANSFORMED_SUFFIX;
            String s1 = from.get(sourceName);
            s1 = (s1 != null) ? s1: "";
            String s2 = to.get(lookupName);
            s2= (s2 != null) ? s2: "";
            boolean fieldMatch = false;
            if (p.isBlanksMatch()){
                fieldMatch = (StringUtils.isBlank(s1) || StringUtils.isBlank(s2));
                if (fieldMatch){
                    logger.debug(sourceName);
                }
            }
            if (!fieldMatch){
                String[] s = new String[2];
                s[0] = s1;
                s[1] = s2;
                Arrays.sort(s);
                fieldMatch = p.getMatcher().matches(s[0], s[1]);
                logger.debug(s[0] + " : " + s[1] + " : " + fieldMatch);
            }
            recordMatch = fieldMatch;
            if (!recordMatch) {
                logger.debug("failed on " + sourceName);
                break;
            }
        }
        return recordMatch;
    }

}