/*
 * Reconciliation and Matching Framework
 * Copyright © 2014 Royal Botanic Gardens, Kew
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.kew.rmf.matchconf;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.EntityManager;
import javax.persistence.OneToMany;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.TypedQuery;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Sort;
import org.hibernate.annotations.SortType;
import org.springframework.roo.addon.javabean.RooJavaBean;
import org.springframework.roo.addon.jpa.activerecord.RooJpaActiveRecord;
import org.springframework.roo.addon.tostring.RooToString;
import org.springframework.transaction.annotation.Transactional;

/**
 * This is the ORM equivalent to
 * {@link org.kew.rmf.core.configuration.Configuration}.
 * Further, it offers a sandbox for all entities in MatchConf guaranteeing
 * their Uniqueness per configuration instance. This facilitates cloning
 * of whole configurations and further tweaking of the clones without messing
 * with the original configuration.
 *
 * NOTE: the uniqueness paradigm is not valid for {@link Dictionary} instances,
 * as they are meant to be available to all configurations.
 */
@RooJavaBean
@RooToString
@RooJpaActiveRecord(finders = { "findConfigurationsByNameEquals" })
@Table(uniqueConstraints=@UniqueConstraint(columnNames={"name"}))
public class Configuration extends CloneMe<Configuration> {

	static String[] CLONE_STRING_FIELDS = new String[] {
		"assessReportFrequency",
		"className",
		"loadReportFrequency",
		"authorityFileDelimiter",
		"authorityFileEncoding",
		"authorityFileName",
		"maxSearchResults",
		"nextConfig",
		"packageName",
		"recordFilter",
		"sortFieldName",
		"queryFileQuoteChar",
		"queryFileDelimiter",
		"queryFileEncoding",
		"queryFileName",
		"workDirPath",
	};

	private String name;
	private String workDirPath;
	private String queryFileName = "query.tsv";
	private String queryFileEncoding = "UTF-8";
	private String queryFileDelimiter = "&#09;";
	private String queryFileQuoteChar = "&quot;";

	private String recordFilter = "";

	private String nextConfig = "";

	// authorityFileName being populated decides over being a MatchConfig
	private String authorityFileName = "";
	private String authorityFileEncoding = "UTF-8";
	private String authorityFileDelimiter = "&#09;";

	private String packageName = "org.kew.rmf.core.configuration";
	private String className = "DeduplicationConfiguration";

	private String loadReportFrequency = "50000";
	private String assessReportFrequency = "100";

	private String sortFieldName = "id";

	private String maxSearchResults = "10000";

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@Sort(type = SortType.NATURAL)
	private List<Wire> wiring = new ArrayList<Wire>();

	@Sort(type = SortType.NATURAL)
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Transformer> transformers = new ArrayList<>();

	@Sort(type = SortType.NATURAL)
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Matcher> matchers = new ArrayList<>();

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Reporter> reporters = new ArrayList<>();

	public Configuration cloneMe () throws Exception {
		Configuration clone = new Configuration();
		// first the string attributes
		String newName = "copy-of_" + this.name;
		while (Configuration.findConfigurationsByNameEquals(newName).getResultList().size() > 0) {
			newName = "copy-of_" + newName;
		}
		clone.setName(newName);
		for (String method:Configuration.CLONE_STRING_FIELDS) {
			clone.setattr(method, this.getattr(method, ""));
		}
		// then the relational attributes
		for (Transformer transformer:this.transformers) {
			clone.getTransformers().add(transformer.cloneMe(clone));
		}
		for (Matcher matcher:this.matchers) {
			clone.getMatchers().add(matcher.cloneMe(clone));
		}
		for (Reporter reporter:this.reporters) {
			Reporter reporterClone = reporter.cloneMe(clone);
			clone.getReporters().add(reporterClone);
		}
		for (Wire wire:this.wiring) {
			clone.getWiring().add(wire.cloneMe(clone));
		}
		clone.persist();
		logger.info("cloned configuration {}", this);
		return clone;
	}

	@PrePersist
	@PreUpdate
	protected void cleanAllPaths() {
		// TODO: change the following local setting to a real local setting :-)
		String cleanedPath = this.getWorkDirPath().replaceAll("\\\\", "/").replaceAll("T:", "/mnt/t_drive");
		this.setWorkDirPath(cleanedPath);
	}

	public static TypedQuery<Configuration> findDedupConfigsByNameEquals(String name) {
		if (name == null || name.length() == 0) throw new IllegalArgumentException("The name argument is required");
		EntityManager em = Configuration.entityManager();
		TypedQuery<Configuration> q = em.createQuery("SELECT o FROM Configuration AS o WHERE o.name = :name AND o.authorityFileName = :authorityFileName", Configuration.class);
		q.setParameter("name", name);
		q.setParameter("authorityFileName", "");
		return q;
	}

	public static TypedQuery<Configuration> findMatchConfigsByNameEquals(String name) {
		if (name == null || name.length() == 0) throw new IllegalArgumentException("The name argument is required");
		EntityManager em = Configuration.entityManager();
		TypedQuery<Configuration> q = em.createQuery("SELECT o FROM Configuration AS o WHERE o.name = :name AND o.authorityFileName != :authorityFileName", Configuration.class);
		q.setParameter("name", name);
		q.setParameter("authorityFileName", "");
		return q;
	}

	public static long countDedupConfigs() {
		TypedQuery<Long> q = entityManager().createQuery("SELECT COUNT(o) FROM Configuration AS o WHERE o.authorityFileName = :authorityFileName", Long.class);
		q.setParameter("authorityFileName", "");
		return q.getSingleResult();
	}

	public static long countMatchConfigs() {
		TypedQuery<Long> q = entityManager().createQuery("SELECT COUNT(o) FROM Configuration AS o WHERE o.authorityFileName != :authorityFileName", Long.class);
		q.setParameter("authorityFileName", "");
		return q.getSingleResult();
	}

	public static List<Configuration> findAllDedupConfigs() {
		EntityManager em = Configuration.entityManager();
		TypedQuery<Configuration> q = em.createQuery("SELECT o FROM Configuration AS o WHERE o.authorityFileName = :authorityFileName", Configuration.class);
		q.setParameter("authorityFileName", "");
		return q.getResultList();
	}

	public static List<Configuration> findAllMatchConfigs() {
		EntityManager em = Configuration.entityManager();
		TypedQuery<Configuration> q = em.createQuery("SELECT o FROM Configuration AS o WHERE o.authorityFileName != :authorityFileName", Configuration.class);
		q.setParameter("authorityFileName", "");
		return q.getResultList();
	}

	public static List<Configuration> findDedupConfigEntries(int firstResult, int maxResults) {
		EntityManager em = Configuration.entityManager();
		TypedQuery<Configuration> q = em.createQuery("SELECT o FROM Configuration AS o WHERE o.authorityFileName = :authorityFileName", Configuration.class);
		q.setParameter("authorityFileName", "");
		return q.setFirstResult(firstResult).setMaxResults(maxResults).getResultList();
	}

	public static List<Configuration> findMatchConfigEntries(int firstResult, int maxResults) {
		EntityManager em = Configuration.entityManager();
		TypedQuery<Configuration> q = em.createQuery("SELECT o FROM Configuration AS o WHERE o.authorityFileName != :authorityFileName", Configuration.class);
		q.setParameter("authorityFileName", "");
		return q.setFirstResult(firstResult).setMaxResults(maxResults).getResultList();
	}

	public Wire getWireForName(String wireName) {
		for (Wire wire : this.getWiring()) {
			if (wire.getName().equals(wireName)) return wire;
		}
		return null;
	}

	public Transformer getTransformerForName(String transformerName) {
		for (Transformer transformer : this.getTransformers()) {
			if (transformer.getName().equals(transformerName)) return transformer;
		}
		return null;
	}

	public Matcher getMatcherForName(String matcherName) {
		for (Matcher matcher : this.getMatchers()) {
			if (matcher.getName().equals(matcherName)) return matcher;
		}
		return null;
	}

	public Reporter getReporterForName(String reporterName) {
		for (Reporter reporter: this.getReporters()) {
			if (reporter.getName().equals(reporterName)) return reporter;
		}
		return null;
	}

	public void removeWire(String wireName) {
		this.getWiring().remove(this.getWireForName(wireName));
		this.merge();
	}

	public void removeTransformers() throws Exception {
		for (Transformer t : this.getTransformers()) {
			this.removeTransformer(t);
		}
	}

	public void removeTransformer(String transformerName) throws Exception {
		Transformer toRemove = this.getTransformerForName(transformerName);
		this.removeTransformer(toRemove);
	}

	public void removeTransformer(Transformer toRemove) throws Exception {
		Wire stillWired = toRemove.hasWiredTransformers();
		if (stillWired != null) {
			throw new Exception(String.format("It seems that %s is still being used in a wire (%s), please remove it there first in order to delete it.", toRemove, stillWired));
		}
		if (this.getTransformers().contains(toRemove)) {
			toRemove.removeOrphanedWiredTransformers();
			this.getTransformers().remove(toRemove);
		}
		this.merge();
	}

	public void removeMatcher(String matcherName) {
		this.getMatchers().remove(this.getMatcherForName(matcherName));
		this.merge();
	}

	public void removeReporter(String reporterName) {
		this.getReporters().remove(this.getReporterForName(reporterName));
		this.merge();
	}

	public List<Dictionary> findDictionaries() {
		List<Dictionary> dicts = new ArrayList<>();
		List<Bot> bots = new ArrayList<>();
		bots.addAll(this.getTransformers());
		bots.addAll(this.getMatchers());
		for (Bot bot:bots) {
			if (!bot.getParams().contains("dict=")) continue;
			for (String param:bot.getParams().split(",")) {
				String[] paramTuple = param.split("=");
				if (paramTuple[0].equals("dict")) dicts.add(Dictionary.findDictionariesByNameEquals(paramTuple[1]).getSingleResult());
			}
		}
		return dicts;
	}

	/**
	 * This method fixes a bug introduced in a very-early-stage migration of the data improvement database. It can possibly be removed
	 * for any new installations and in near future in general.
	 */
	public void fixMatchersForAlienWire() {
		for (Matcher matcher:this.getMatchers()) {
			for (Wire possibleAlienWire:Wire.findWiresByMatcher(matcher).getResultList()) {
				Configuration possibleAlienConfig = possibleAlienWire.getConfiguration();
				if (!possibleAlienConfig.getId().equals(this.getId())) {
					logger.warn("Still found an alien wire ({} from config {}!!! trying to fix that now", possibleAlienWire.getName(), this.getName());
					possibleAlienWire.setMatcher(possibleAlienConfig.getMatcherForName(matcher.getName()));
					possibleAlienWire.merge();
					logger.info("Released alien wire into the void");
				}
			}

		}
	}

	@Transactional
	public void remove() {
		if (this.entityManager == null) this.entityManager = entityManager();
		if (this.entityManager.contains(this)) {
			this.entityManager.remove(this);
		} else {
			Configuration attached = Configuration.findConfiguration(this.getId());
			this.entityManager.remove(attached);
		}
	}
}
