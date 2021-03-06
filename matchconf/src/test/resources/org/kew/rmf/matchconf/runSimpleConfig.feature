# Copyright © 2014 Royal Botanic Gardens, Kew.  See LICENSE.md for details.

Feature: run a simple configuration
    Scenario: run the configuration as set up by createSimpleConfig step
        Given Alecs has set up a simple Configuration resulting in the following config "config_simple-config-to-run.xml":
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xmlns:util="http://www.springframework.org/schema/util"
                xmlns:p="http://www.springframework.org/schema/p"
                xmlns:c="http://www.springframework.org/schema/c"
                xsi:schemaLocation="
                    http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-3.1.xsd
                    http://www.springframework.org/schema/util http://www.springframework.org/schema/util/spring-util-3.1.xsd">
                <bean id="preferencePlaceHolder" class="org.springframework.beans.factory.config.PreferencesPlaceholderConfigurer">
                    <property name="locations">
                        <list>
                        </list>
                    </property>
                </bean>
                <bean id="lucene_directory" class="java.lang.String">
                    <constructor-arg value="target/deduplicator"/>
                </bean>
                <bean id="queryfile" class="java.io.File">
                    <constructor-arg value="REPLACE_WITH_TMPDIR/query.tsv" />
                </bean>
                <bean id="matchExactly" class="org.kew.rmf.matchers.ExactMatcher" />
                <bean id="a2BTransformer" class="org.kew.rmf.transformers.A2BTransformer"
                    p:a="a"
                    p:b="" />
                <bean id="anotherTransformer" class="org.kew.rmf.transformers.StripNonAlphabeticCharactersTransformer" />
                <util:list id="reporters">
                    <bean class="org.kew.rmf.reporters.DedupReporter"
                        p:name="outputReporter"
                        p:configName="simple-config-to-run"
                        p:delimiter="&#09;"
                        p:idDelimiter="|">
                        <property name="file">
                            <bean class="java.io.File">
                                <constructor-arg value="REPLACE_WITH_TMPDIR/output.tsv" />
                            </bean>
                        </property>
                    </bean>
                    <bean class="org.kew.rmf.reporters.DedupReporterMultiline"
                        p:name="outputReporterMultiline"
                        p:delimiter="&#09;"
                        p:idDelimiter="|">
                        <property name="file">
                            <bean class="java.io.File">
                                <constructor-arg value="REPLACE_WITH_TMPDIR/output_multiline.tsv" />
                            </bean>
                        </property>
                    </bean>
                </util:list>
                <util:list id="columnProperties">
                    <bean class="org.kew.rmf.core.configuration.Property"
                        p:queryColumnName="data_col"
                        p:useInSelect="true"
                        p:addOriginalQueryValue="true"
                        p:matcher-ref="matchExactly">
                        <property name="queryTransformers">
                            <util:list id="1">
                                <ref bean="a2BTransformer"/>
                                <ref bean="anotherTransformer"/>
                            </util:list>
                        </property>
                    </bean>
                </util:list>
                <bean id="config" class="org.kew.rmf.core.configuration.DeduplicationConfiguration"
                    p:queryFile-ref="queryfile"
                    p:properties-ref="columnProperties"
                    p:sortFieldName="id"
                    p:queryFileEncoding="UTF-8"
                    p:queryFileDelimiter="&#09;"
                    p:queryFileQuoteChar="&quot;"
                    p:loadReportFrequency="50000"
                    p:assessReportFrequency="100"
                    p:reporters-ref="reporters"/>

                <!-- import the generic application-context (equal for dedup/match configurations) -->
                <import resource="classpath*:application-context.xml"/>
                <!-- add the deduplication-specific bit -->
                <import resource="classpath*:application-context-dedup.xml"/>

            </beans>
            """
        And some mysterious data-improver has put a file "query.tsv" in the same directory containing the following data:
            | id      | data_col  | transformer_comments                               | matcher_comments |
            | 1       | 0         | zero should be replaced with blank                 | stays alone      |
            | 2       | some-name | hyphen should be replaced with white space         | 3 cluster items  |
            | 3       |           | blank stays blank                                  | stays alone      |
            | 4       | some name | stays the same                                     | 3 cluster items  |
            | 5       |           | blank stays blank                                  | stays alone      |
            | 6       | sóme namê | diacrits should be replaced with ascii equivalents | 3 cluster items  |
        When asking MatchConf to run this configuration
        Then the deduplication program should run smoothly and produce the following file "output.tsv" in the same directory:
            | id      | data_col  | cluster_size  | from_id                      | ids_in_cluster |
            | 1       | 0         | 1             | 1                            | 1              |
            | 6       | sóme namê | 3             | 2                            | 6 \| 4 \| 2    |
            | 3       |           | 1             | 3                            | 3              |
            | 5       |           | 1             | 5                            | 5              |
