/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.owasp.dependencytrack.tasks;

import alpine.Config;
import alpine.event.framework.Event;
import alpine.event.framework.LoggableSubscriber;
import alpine.event.framework.SingleThreadedEventService;
import alpine.logging.Logger;
import alpine.util.JavaVersion;
import alpine.util.SystemUtil;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.owasp.dependencytrack.event.IndexEvent;
import org.owasp.dependencytrack.event.NspMirrorEvent;
import org.owasp.dependencytrack.model.Vulnerability;
import org.owasp.dependencytrack.parser.nsp.NspAdvsoriesParser;
import org.owasp.dependencytrack.parser.nsp.model.Advisory;
import org.owasp.dependencytrack.parser.nsp.model.AdvisoryResults;
import org.owasp.dependencytrack.persistence.QueryManager;
import org.owasp.dependencytrack.util.HttpClientFactory;
import us.springett.cvss.Cvss;
import us.springett.cvss.CvssV2;
import us.springett.cvss.CvssV3;
import us.springett.cvss.Score;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Date;

/**
 * Subscriber task that performs a mirror of the Node Security Platform public advisories.
 *
 * @author Steve Springett
 * @since 3.0.0
 */
public class NspMirrorTask implements LoggableSubscriber {

    private static final String NSP_API_BASE_URL = "https://api.nodesecurity.io/advisories";
    private static final Logger LOGGER = Logger.getLogger(NspMirrorTask.class);

    /**
     * {@inheritDoc}
     */
    public void inform(Event e) {
        if (e instanceof NspMirrorEvent) {
            LOGGER.info("Starting NSP mirroring task");

            //todo: remove this check when Java 9 is eventually a requirement
            JavaVersion javaVersion = SystemUtil.getJavaVersion();
            if (javaVersion.getMajor() == 8 && javaVersion.getUpdate() < 101) {
                LOGGER.error("Unable to mirror contents of Node Security Platform. NSP requires Java 1.8.0_101 or higher.");
            } else {
                getAdvisories();
            }

            LOGGER.info("NSP mirroring complete");
        }
    }

    /**
     * Performs an incremental mirror (using pagination) of the NSP public advisory database.
     */
    private void getAdvisories() {
        final Date currentDate = new Date();
        LOGGER.info("Retrieving NSP advisories at " + currentDate);

        try {
            Unirest.setHttpClient(HttpClientFactory.createClient());

            boolean more = true;
            int offset = 0;
            while (more) {
                LOGGER.info("Retrieving NSP advisories from " + NSP_API_BASE_URL);
                final HttpResponse<JsonNode> jsonResponse = Unirest.get(NSP_API_BASE_URL)
                        .header("accept", "application/json")
                        .queryString("offset", offset)
                        .asJson();

                if (jsonResponse.getStatus() == 200) {
                    final NspAdvsoriesParser parser = new NspAdvsoriesParser();
                    final AdvisoryResults results = parser.parse(jsonResponse.getBody());
                    updateDatasource(results);
                    more = results.getCount() + results.getOffset() != results.getTotal();
                    offset += results.getCount();
                }
            }
        } catch (UnirestException e) {
            LOGGER.error("An error occurred while retrieving NSP advisory", e);
        }
    }

    /**
     * Synchronizes the advisories that were downloaded with the internal Dependency-Track database.
     * @param results the results to synchronize
     */
    private void updateDatasource(AdvisoryResults results) {
        LOGGER.info("Updating datasource with NSP advisories");
        try (QueryManager qm = new QueryManager()) {
            for (Advisory advisory: results.getAdvisories()) {
                qm.synchronizeVulnerability(mapAdvisoryToVulnerability(advisory), false);
            }
        }
        SingleThreadedEventService.getInstance().publish(new IndexEvent(IndexEvent.Action.COMMIT, Vulnerability.class));
    }

    /**
     * Helper method that maps an NSP advisory object to a Dependency-Track vulnerability object.
     * @param advisory the NSP advisory to map
     * @return a Dependency-Track Vulnerability object
     */
    private Vulnerability mapAdvisoryToVulnerability(Advisory advisory) {
        final Vulnerability vuln = new Vulnerability();
        vuln.setSource(Vulnerability.Source.NSP);
        vuln.setVulnId(String.valueOf(advisory.getId()));
        vuln.setDescription(advisory.getOverview());
        vuln.setTitle(advisory.getTitle());
        vuln.setSubTitle(advisory.getModuleName());

        if (StringUtils.isNotBlank(advisory.getCreatedAt())) {
            final OffsetDateTime odt = OffsetDateTime.parse(advisory.getCreatedAt());
            vuln.setCreated(Date.from(odt.toInstant()));
        }
        if (StringUtils.isNotBlank(advisory.getPublishDate())) {
            final OffsetDateTime odt = OffsetDateTime.parse(advisory.getPublishDate());
            vuln.setPublished(Date.from(odt.toInstant()));
        }
        if (StringUtils.isNotBlank(advisory.getUpdatedAt())) {
            final OffsetDateTime odt = OffsetDateTime.parse(advisory.getUpdatedAt());
            vuln.setUpdated(Date.from(odt.toInstant()));
        }

        final Cvss cvss = Cvss.fromVector(advisory.getCvssVector());
        if (cvss != null) {
            final Score score = cvss.calculateScore();
            if (cvss instanceof CvssV2) {
                vuln.setCvssV2Vector(cvss.getVector());
                vuln.setCvssV2BaseScore(BigDecimal.valueOf(score.getBaseScore()));
                vuln.setCvssV2ImpactSubScore(BigDecimal.valueOf(score.getImpactSubScore()));
                vuln.setCvssV2ExploitabilitySubScore(BigDecimal.valueOf(score.getExploitabilitySubScore()));
            } else if (cvss instanceof CvssV3) {
                vuln.setCvssV3Vector(cvss.getVector());
                vuln.setCvssV3BaseScore(BigDecimal.valueOf(score.getBaseScore()));
                vuln.setCvssV3ImpactSubScore(BigDecimal.valueOf(score.getImpactSubScore()));
                vuln.setCvssV3ExploitabilitySubScore(BigDecimal.valueOf(score.getExploitabilitySubScore()));
            }
        }

        vuln.setCredits(advisory.getAuthor());
        vuln.setRecommendation(advisory.getRecommendation());
        vuln.setReferences(advisory.getReferences());
        vuln.setVulnerableVersions(advisory.getVulnerableVersions());
        vuln.setPatchedVersions(advisory.getPatchedVersions());

        return vuln;
    }

}
