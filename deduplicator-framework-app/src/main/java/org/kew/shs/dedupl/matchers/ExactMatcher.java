package org.kew.shs.dedupl.matchers;

import org.kew.shs.dedupl.util.LibraryRegister;

/**
 * This matcher tests for equality between the two inputs (exact matches).
 * @author nn00kg
 *
 */
@LibraryRegister(category="matchers")
public class ExactMatcher implements Matcher {

	public static int COST = 0;
	protected boolean blanksMatch = true;
	
	public int getCost() {
		return COST;
	}

	public boolean matches(String s1, String s2) {
		boolean matches = false;
		if (s1 == null && s2 == null && this.blanksMatch) {
			matches = true;
		} else{
			try{
				matches = s1.equals(s2);
			}
			catch (Exception e) {
				;
			}
		}
		return matches;
	}

	public boolean isExact() {
		return true;
	}

	public String getExecutionReport() {
		return null;
	}
	
	public void setBlanksMatch(boolean blanksMatch) {
		this.blanksMatch = blanksMatch;
	}

}