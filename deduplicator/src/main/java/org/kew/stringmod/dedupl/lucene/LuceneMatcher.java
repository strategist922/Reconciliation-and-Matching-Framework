package org.kew.stringmod.dedupl.lucene;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.kew.stringmod.dedupl.DataHandler;
import org.kew.stringmod.dedupl.configuration.Configuration;
import org.kew.stringmod.dedupl.configuration.MatchConfiguration;
import org.kew.stringmod.dedupl.configuration.Property;
import org.kew.stringmod.dedupl.reporters.LuceneReporter;
import org.kew.stringmod.dedupl.reporters.Piper;
import org.kew.stringmod.lib.transformers.Transformer;
import org.supercsv.io.CsvMapReader;
import org.supercsv.prefs.CsvPreference;


public class LuceneMatcher extends LuceneHandler<MatchConfiguration> implements DataHandler<MatchConfiguration> {

    protected MatchConfiguration matchConfig;

    public void loadData() throws Exception{ // from DataMatcher
        if (!getConfig().isReuseIndex()){
            this.dataLoader.setConfig(this.getConfig());
            this.dataLoader.load(this.getConfig().getStoreFile());
        }
        else
            this.logger.info("Reusing existing index");
    }

    public List<Map<String,String>> getMatches(Map<String, String> record, int numMatches) throws Exception {
        // pipe everything through to the output where an existing filter evals to false;
        if (!StringUtils.isBlank(config.getRecordFilter()) && !jsEnv.evalFilter(config.getRecordFilter(), record)) {
            return null;
        }
        // transform fields where required
        for (Property prop:config.getProperties()) {
            String fName = prop.getSourceColumnName();
            String fValue = record.get(fName);
            // transform the field-value..
            fValue = fValue == null ? "" : fValue; // super-csv treats blank as null, we don't for now
            for (Transformer t:prop.getSourceTransformers()) {
                fValue = t.transform(fValue);
            }
            // ..and put it into the record
            record.put(fName + Configuration.TRANSFORMED_SUFFIX, fValue);
        }

        String fromId = record.get(Configuration.ID_FIELD_NAME);
        // Use the properties to select a set of documents which may contain matches
        String querystr = LuceneUtils.buildQuery(config.getProperties(), record, false);
        // If the query for some reasons results being empty we pipe the record directly through to the output
        // TODO: create a log-file that stores critical log messages?
        if (querystr.equals("")) {
            logger.warn("Empty query for record {}", record);
            return null;
        }

        TopDocs td = queryLucene(querystr, this.getIndexSearcher(), config.getMaxSearchResults());
        if (td.totalHits == config.getMaxSearchResults()) {
            throw new Exception(String.format("Number of max search results exceeded for record %s! You should either tweak your config to bring back less possible results making better use of the \"useInSelect\" switch (recommended) or raise the \"maxSearchResults\" number.", record));
        }
        this.logger.debug("Found " + td.totalHits + " possibles to assess against " + fromId);

        List<Map<String, String>> matches = new ArrayList<>();

        for (ScoreDoc sd : td.scoreDocs){
            Document toDoc = getFromLucene(sd.doc);
            if (LuceneUtils.recordsMatch(record, toDoc, config.getProperties())) {
                numMatches++;
                matches.add(LuceneUtils.doc2Map(toDoc));
            }
        }
        sortMatches(matches);
        return matches;
    }

    public void sortMatches(List<Map<String, String>> matches) {
        final String sortOn = config.getScoreFieldName();
        try {
            Collections.sort(matches, Collections.reverseOrder(new Comparator<Map<String, String>>() {
                public int compare(final Map<String, String> m1,final Map<String, String> m2) {
                    return Integer.valueOf(m1.get(sortOn)).compareTo(Integer.valueOf(m2.get(sortOn)));
                }
            }));
        } catch (NumberFormatException e) {
            // if the String can't be converted to an integer we do String comparison
            Collections.sort(matches, Collections.reverseOrder(new Comparator<Map<String, String>>() {
                public int compare(final Map<String, String> m1,final Map<String, String> m2) {
                    return m1.get(sortOn).compareTo(m2.get(sortOn));
                }
            }));
        }
    }

    /**
     * Run the whole matching task.
     *
     * The iterative flow is:
     * - load the data (== write the lucene index)
     * - iterate over the source data file
     *     - for each record, look for matches in the index
     *    - for each record, report into new fields of this record about matches via reporters
     *
     * The main difference to a deduplication task as defined by {@link LuceneDeduplicator}
     * is that we use two different datasets, one to create the lookup index, the other one as
     * source file (where we iterate over each record to look up possible matches).
     */
    public void run() throws Exception {

        this.loadData(); // writes the index according to the configuration

        // TODO: either make quote characters and line break characters configurable or simplify even more?
        CsvPreference csvPref = new CsvPreference.Builder(
                '"', this.getConfig().getSourceFileDelimiter().charAt(0), "\n").build();
        int i = 0;
        try (MatchConfiguration config = this.getConfig();
             IndexReader indexReader= this.getIndexReader();
             IndexWriter indexWriter = this.indexWriter;
             CsvMapReader mr = new CsvMapReader(new FileReader(this.getConfig().getSourceFile()), csvPref)) {

            this.prepareEnvs();

            // loop over the sourceFile
            int numMatches = 0;
            final String[] header = mr.getHeader(true);
            // check whether the header column names fit to the ones specified in the configuration
            List<String> headerList = Arrays.asList(header);
            for (String name:this.config.getPropertySourceColumnNames()) {
                if (!headerList.contains(name)) throw new Exception(String.format("%s: Header doesn't contain field name < %s > as defined in config.", this.config.getSourceFile().getPath(), name));
            }
            // same for the id-field
            String idFieldName = Configuration.ID_FIELD_NAME;
            if (!headerList.contains(idFieldName)) throw new Exception(String.format("%s: Id field name not found in header, should be %s!", this.config.getSourceFile().getPath(), idFieldName));
            Map<String, String> record;
            while((record = mr.read(header)) != null) {
                List<Map<String, String>> matches = getMatches(record, numMatches);
                if (matches == null) {
                    for (Piper piper:config.getPipers()) piper.pipe(record);
                    continue;
                }
                if (i++ % config.getAssessReportFrequency() == 0) this.logger.info("Assessed " + i + " records, found " + numMatches + " matches");
                // call each reporter that has a say; all they get is a complete list of duplicates for this record.
                for (LuceneReporter reporter : config.getReporters()) {
                    // TODO: make idFieldName configurable, but not on reporter level
                    reporter.report(record, matches);
                }
            }
            this.logger.info("Assessed " + i + " records, found " + numMatches + " matches");
        }
    }

}