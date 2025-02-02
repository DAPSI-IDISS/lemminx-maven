/*******************************************************************************
 * Copyright (c) 2020-2021 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.PROJECT_ELT;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.DefaultProjectBuilder;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.utils.DOMUtils;

public class MavenProjectCache {

	private static final Logger LOGGER = Logger.getLogger(MavenProjectCache.class.getName());

	private final Map<URI, Integer> lastCheckedVersion;
	private final Map<URI, MavenProject> projectCache;
	private final Map<URI, Collection<ModelProblem>> problemCache;

	private final MavenLemminxExtension lemminxMavenPlugin;
	private final PlexusContainer plexusContainer;
	private final MavenExecutionRequest mavenRequest;

	MavenXpp3Reader mavenReader = new MavenXpp3Reader();
	private ProjectBuilder projectBuilder;

	private final List<Consumer<MavenProject>> projectParsedListeners = new ArrayList<>();
	private Properties systemProperties;

	public MavenProjectCache(MavenLemminxExtension lemminxMavenPlugin) {
		this.lemminxMavenPlugin = lemminxMavenPlugin;
		this.mavenRequest = lemminxMavenPlugin.getMavenSession().getRequest();
		this.plexusContainer = lemminxMavenPlugin.getPlexusContainer();
		this.lastCheckedVersion = new HashMap<>();
		this.projectCache = new HashMap<>();
		this.problemCache = new HashMap<>();
		systemProperties = new Properties();
		EnvironmentUtils.addEnvVars(systemProperties);
		systemProperties.putAll(System.getProperties());
		initializeMavenBuildState();
	}

	/**
	 *
	 * @param document
	 * @return the last MavenDocument that could be build for the more recent
	 *         version of the provided document. If document fails to build a
	 *         MavenProject, a former version will be returned. Can be
	 *         <code>null</code>.
	 */
	public MavenProject getLastSuccessfulMavenProject(DOMDocument document) {
		check(document);
		return projectCache.get(URI.create(document.getTextDocument().getUri()));
	}

	/**
	 *
	 * @param document
	 * @return the problems for the latest version of the document (either in cache,
	 *         or the one passed in arguments)
	 */
	public Collection<ModelProblem> getProblemsFor(DOMDocument document) {
		check(document);
		return problemCache.get(URI.create(document.getTextDocument().getUri()));
	}

	private void check(DOMDocument document) {
		Integer last = lastCheckedVersion.get(URI.create(document.getTextDocument().getUri()));
		if (last == null || last.intValue() < document.getTextDocument().getVersion()) {
			parseAndCache(document);
		}
	}

	/**
	 * Parses and adds a document for a given URI into the projects cache
	 * 
	 * @param uri An URI of a document to add
	 * @param document A document to add
	 */
	public void addDocument(URI uri) {
		DOMDocument document = DOMUtils.loadDocument(uri.toString(), lemminxMavenPlugin.getUriResolveExtentionManager());
		if (document != null) {
			DOMElement documentElement = document.getDocumentElement();
			if (documentElement != null && PROJECT_ELT.equals(documentElement.getLocalName())) {
				parseAndCache(document);
			}
		}
	}
	
	/**
	 * Removes a document for a given URI from the projects cache
	 * @param uri An URI of a document to remove
	 */
	public void removeDocument(URI uri) {
		projectCache.remove(uri);
		problemCache.remove(uri);
	}

	public Optional<MavenProject> getSnapshotProject(File file) {
		MavenProject lastKnownVersionMavenProject = projectCache.get(file.toURI());
		if (lastKnownVersionMavenProject != null) {
			return Optional.of(lastKnownVersionMavenProject);
		}
		try {
			MavenProject project = projectBuilder.build(file, newProjectBuildingRequest()).getProject();
			return Optional.of(project);
		} catch (ProjectBuildingException e) {
			List<ProjectBuildingResult> result = e.getResults();
			if (result != null && result.size() == 1 && result.get(0).getProject() != null) {
				MavenProject project = result.get(0).getProject();
				return Optional.of(project);
			}
		}
		return Optional.empty();
	}

	private void parseAndCache(DOMDocument document) {
		URI uri = URI.create(document.getDocumentURI());
		Collection<ModelProblem> problems = new ArrayList<>();
		try {
			ProjectBuildingResult buildResult = projectBuilder.build(new FileModelSource(new File(uri)) {
				@Override
				public InputStream getInputStream() throws IOException {
					return new ByteArrayInputStream(document.getText().getBytes());
				}
			}, newProjectBuildingRequest());
			problems.addAll(buildResult.getProblems());
			if (buildResult.getProject() != null) {
				// setFile should ideally be invoked during project build, but related methods
				// to pass modelSource and pomFile are private
				buildResult.getProject().setFile(new File(uri));
				projectCache.put(uri, buildResult.getProject());
				projectParsedListeners.forEach(listener -> listener.accept(buildResult.getProject()));
			}
		} catch (ProjectBuildingException e) {
			if (e.getResults() == null) {
				if (e.getCause() instanceof ModelBuildingException) {
					// Try to manually build a minimal project from the document to collect lower-level
					// errors and to have something usable in cache for most basic operations
					ModelBuildingException modelBuildingException = (ModelBuildingException) e.getCause();
					problems.addAll(modelBuildingException.getProblems());
					File file = new File(uri);
					try (ByteArrayInputStream documentStream = new ByteArrayInputStream(
							document.getText().getBytes())) {
						Model model = mavenReader.read(documentStream);
						MavenProject project = new MavenProject(model);
						project.setRemoteArtifactRepositories(model.getRepositories().stream()
								.map(repo -> new MavenArtifactRepository(repo.getId(), repo.getUrl(),
										new DefaultRepositoryLayout(),
										new ArtifactRepositoryPolicy(true,
												ArtifactRepositoryPolicy.UPDATE_POLICY_INTERVAL,
												ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN),
										new ArtifactRepositoryPolicy(true,
												ArtifactRepositoryPolicy.UPDATE_POLICY_INTERVAL,
												ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN)))
								.distinct().collect(Collectors.toList()));
						project.setFile(file);
						project.setBuild(new Build());
						projectCache.put(uri, project);
						projectParsedListeners.forEach(listener -> listener.accept(project));
					} catch (XmlPullParserException parserException) {
						// XML document is invalid fo parsing (eg user is typing), it's a valid state that shouldn't log
						// exceptions
					} catch (IOException ex) {
						LOGGER.log(Level.SEVERE, e.getMessage(), e);
					}
				} else {
					problems.add(
							new DefaultModelProblem(e.getMessage(), Severity.FATAL, Version.BASE, null, -1, -1, e));
				}
			} else {
				e.getResults().stream().flatMap(result -> result.getProblems().stream()).forEach(problems::add);
				if (e.getResults().size() == 1) {
					MavenProject project = e.getResults().get(0).getProject();
					if (project != null) {
						project.setFile(new File(uri));
						projectCache.put(uri, project);
						projectParsedListeners.forEach(listener -> listener.accept(project));
					}
				}
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}

		lastCheckedVersion.put(uri, document.getTextDocument().getVersion());
		problemCache.put(uri, problems);
	}

	private ProjectBuildingRequest newProjectBuildingRequest() {
		ProjectBuildingRequest request = new DefaultProjectBuildingRequest();
		request.setSystemProperties(systemProperties);
		request.setLocalRepository(mavenRequest.getLocalRepository());
		request.setRemoteRepositories(mavenRequest.getRemoteRepositories());
		request.setPluginArtifactRepositories(mavenRequest.getPluginArtifactRepositories());
		// TODO more to transfer from mavenRequest to ProjectBuildingRequest?
		request.setRepositorySession(lemminxMavenPlugin.getMavenSession().getRepositorySession());
		request.setResolveDependencies(true);
		return request;
	}

	private void initializeMavenBuildState() {
		if (projectBuilder != null) {
			return;
		}
		try {
			projectBuilder = getPlexusContainer().lookup(ProjectBuilder.class);
			System.setProperty(DefaultProjectBuilder.DISABLE_GLOBAL_MODEL_CACHE_SYSTEM_PROPERTY, Boolean.toString(true));
		} catch (ComponentLookupException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
	}

	public PlexusContainer getPlexusContainer() {
		return plexusContainer;
	}

	public Collection<MavenProject> getProjects() {
		return projectCache.values();
	}

	public MavenProject getSnapshotProject(DOMDocument document, String profileId) {
		// it would be nice to directly rebuild from Model instead of reparsing text
		ProjectBuildingRequest request = newProjectBuildingRequest();
		if (profileId != null) {
			request.setActiveProfileIds(List.of(profileId));
		}
		try {
			return projectBuilder.build(new FileModelSource(new File(document.getDocumentURI())) {
				@Override
				public InputStream getInputStream() throws IOException {
					return new ByteArrayInputStream(document.getText().getBytes());
				}
			}, request).getProject();
		} catch (ProjectBuildingException e) {
			List<ProjectBuildingResult> result = e.getResults();
			if (result != null && result.size() == 1 && result.get(0).getProject() != null) {
				return result.get(0).getProject();
			}
		}
		return null;
	}
}
