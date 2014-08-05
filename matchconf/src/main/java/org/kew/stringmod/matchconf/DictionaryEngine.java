package org.kew.stringmod.matchconf;

import java.util.ArrayList;

/**
 * Writes out the xml to configure the 'real' deduplication/matching process of this specific
 * {@link Dictionary} instance.
 */
public class DictionaryEngine {
	
	Dictionary dict;
	
	public DictionaryEngine(Dictionary dict) {
		this.dict = dict;
	}
	
	public ArrayList<String> toXML(int indentLevel) throws Exception {
		int shiftWidth = 4;
		String shift = String.format("%" + shiftWidth + "s", " ");
		String indent = "";
		for (int i=0;i<indentLevel;i++) {
			indent += shift;
		}
		ArrayList<String> outXML = new ArrayList<String>();
		outXML.add(String.format("%s<bean id=\"%s\" class=\"org.kew.rmf.utils.CsvDictionary\"", indent, this.dict.getName()));
        outXML.add(String.format("%s%sp:fileDelimiter=\"%s\"", indent, shift, this.dict.getFileDelimiter()));
        // change path to unix-style for convencience, even if on windows..
        outXML.add(String.format("%s%sp:filePath=\"%s\" />", indent, shift, this.dict.getFilePath().replace("\\\\", "/")));
		return outXML;
	}
}
