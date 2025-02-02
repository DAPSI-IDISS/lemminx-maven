/*******************************************************************************
 * Copyright (c) 2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.maven.Maven;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.model.validation.ModelValidator;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.SettingsReader;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.classworlds.realm.NoSuchRealmException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.maven.participants.completion.MavenCompletionParticipant;
import org.eclipse.lemminx.extensions.maven.participants.definition.MavenDefinitionParticipant;
import org.eclipse.lemminx.extensions.maven.participants.diagnostics.MavenDiagnosticParticipant;
import org.eclipse.lemminx.extensions.maven.participants.hover.MavenHoverParticipant;
import org.eclipse.lemminx.extensions.maven.searcher.LocalRepositorySearcher;
import org.eclipse.lemminx.extensions.maven.searcher.RemoteRepositoryIndexSearcher;
import org.eclipse.lemminx.services.extensions.ICompletionParticipant;
import org.eclipse.lemminx.services.extensions.IHoverParticipant;
import org.eclipse.lemminx.services.extensions.IXMLExtension;
import org.eclipse.lemminx.services.extensions.XMLExtensionsRegistry;
import org.eclipse.lemminx.services.extensions.diagnostics.IDiagnosticsParticipant;
import org.eclipse.lemminx.services.extensions.save.ISaveContext;
import org.eclipse.lemminx.services.extensions.save.ISaveContext.SaveContextType;
import org.eclipse.lemminx.settings.AllXMLSettings;
import org.eclipse.lemminx.settings.InitializationOptionsSettings;
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;

import com.google.common.base.Objects;

/**
 * Extension for pom.xml.
 *
 */
public class MavenLemminxExtension implements IXMLExtension {

	private static final Logger LOGGER = Logger.getLogger(MavenLemminxExtension.class.getName());
	private static final String MAVEN_XMLLS_EXTENSION_REALM_ID = MavenLemminxExtension.class.getName();

	private XMLExtensionsRegistry currentRegistry;

	private ICompletionParticipant completionParticipant;
	private IDiagnosticsParticipant diagnosticParticipant;
	private IHoverParticipant hoverParticipant;
	private MavenDefinitionParticipant definitionParticipant;
	private MavenWorkspaceService workspaceServiceParticipant;

	private MavenProjectCache cache;
	private RemoteRepositoryIndexSearcher indexSearcher;
	private LocalRepositorySearcher localRepositorySearcher;
	private MavenExecutionRequest mavenRequest;
	private MavenPluginManager mavenPluginManager;
	private PlexusContainer container;
	private MavenSession mavenSession;
	private BuildPluginManager buildPluginManager;

	XMLMavenSettings settings = new XMLMavenSettings();
	private URIResolverExtensionManager resolverExtensionManager;
	private List<WorkspaceFolder> initialWorkspaceFolders = List.of();

	@Override
	public void doSave(ISaveContext context) {
		if (context.getType() == SaveContextType.SETTINGS) {
			final XMLExtensionsRegistry registry = this.currentRegistry; // keep ref as this.currentRegistry becomes null on stop
			XMLMavenGeneralSettings generalXMLSettings = XMLMavenGeneralSettings.getGeneralXMLSettings(context.getSettings());
			if (generalXMLSettings == null) {
				return;
			}
			XMLMavenSettings newSettings = generalXMLSettings.getMaven();
			if (newSettings != null && !Objects.equal(this.settings, newSettings)) {
				stop(registry);
				this.settings = newSettings;
				start(null, registry);
			}
		}
	}

	@Override
	public void start(InitializeParams params, XMLExtensionsRegistry registry) {
		if (params != null) {
			this.initialWorkspaceFolders = params.getWorkspaceFolders();
			Object initOptions = InitializationOptionsSettings.getSettings(params);
			Object xmlSettings = AllXMLSettings.getAllXMLSettings(initOptions);
			XMLMavenGeneralSettings generalXmlSettings = XMLMavenGeneralSettings.getGeneralXMLSettings(xmlSettings);
			if (generalXmlSettings != null) {
				settings = generalXmlSettings.getMaven();
			}
		}
		this.currentRegistry = registry;
		this.resolverExtensionManager = registry.getResolverExtensionManager();
		try {
			// Do not invoke getters the MavenLemminxExtension in participant constructors,
			// or that will trigger loading of plexus, Maven and so on even for non pom files
			// Initialization will happen when calling getters.
			workspaceServiceParticipant = new MavenWorkspaceService(this);
			registry.registerWorkspaceServiceParticipant(workspaceServiceParticipant);
			completionParticipant = new MavenCompletionParticipant(this);
			registry.registerCompletionParticipant(completionParticipant);
			diagnosticParticipant = new MavenDiagnosticParticipant(this);
			registry.registerDiagnosticsParticipant(diagnosticParticipant);
			hoverParticipant = new MavenHoverParticipant(this);
			registry.registerHoverParticipant(hoverParticipant);
			definitionParticipant = new MavenDefinitionParticipant(this);
			registry.registerDefinitionParticipant(definitionParticipant);
		} catch (Exception ex) {
			LOGGER.log(Level.SEVERE, ex.getCause().toString(), ex);
		}
	}

	private synchronized void initialize() {
		if (mavenSession != null) {
			// already initialized
			return;
		}
		try {
			this.container = newPlexusContainer();
			this.container.addComponent(new ModelValidatorMNG7170(), ModelValidator.class, "workaround-MNG7170");
			mavenRequest = initMavenRequest(container, settings);
			DefaultRepositorySystemSessionFactory repositorySessionFactory = container.lookup(DefaultRepositorySystemSessionFactory.class);
			RepositorySystemSession repositorySystemSession = repositorySessionFactory.newRepositorySession(mavenRequest);
			MavenExecutionResult mavenResult = new DefaultMavenExecutionResult();
			// TODO: MavenSession is deprecated. Investigate for alternative
			mavenSession = new MavenSession(container, repositorySystemSession, mavenRequest, mavenResult);
			cache = new MavenProjectCache(this);
			localRepositorySearcher = new LocalRepositorySearcher(mavenSession.getRepositorySession().getLocalRepository().getBasedir());
			if (!settings.getIndex().isSkip()) {
				indexSearcher = new RemoteRepositoryIndexSearcher(this, container, Optional.ofNullable(settings.getIndexLocation()).map(File::new));
			}
			buildPluginManager = null;
			mavenPluginManager = container.lookup(MavenPluginManager.class);
			buildPluginManager = container.lookup(BuildPluginManager.class);
			didChangeWorkspaceFolders(this.initialWorkspaceFolders.stream().map(WorkspaceFolder::getUri).map(URI::create).toArray(URI[]::new), null);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
			stop(currentRegistry);
		}
	}

	private MavenExecutionRequest initMavenRequest(PlexusContainer container, XMLMavenSettings options) throws Exception {
		MavenExecutionRequest mavenRequest = new DefaultMavenExecutionRequest();
		SettingsReader reader = container.lookup(SettingsReader.class);
		MavenExecutionRequestPopulator requestPopulator = container.lookup(MavenExecutionRequestPopulator.class);
		{
			final File globalSettingsFile = getFileFromOptions(options.getGlobalSettings(), SettingsXmlConfigurationProcessor.DEFAULT_GLOBAL_SETTINGS_FILE);
			if (globalSettingsFile.canRead()) {
				mavenRequest.setGlobalSettingsFile(globalSettingsFile);
				Settings globalSettings = reader.read(globalSettingsFile, null);
				requestPopulator.populateFromSettings(mavenRequest, globalSettings);
			}
		}
		{
			final File localSettingsFile = getFileFromOptions(options.getUserSettings(), SettingsXmlConfigurationProcessor.DEFAULT_USER_SETTINGS_FILE);
			if (localSettingsFile.canRead()) {
				mavenRequest.setUserSettingsFile(localSettingsFile);
				Settings userSettings = reader.read(localSettingsFile, null);
				requestPopulator.populateFromSettings(mavenRequest, userSettings);
			}
		}

		String localRepoProperty = System.getProperty("maven.repo.local");
		if (localRepoProperty != null) {
			mavenRequest.setLocalRepositoryPath(new File(localRepoProperty));
		}
		String localRepoOption = options.getRepo().getLocal();
		if (localRepoOption != null && !localRepoOption.trim().isEmpty()) {
			File candidate = new File(localRepoOption);
			if (candidate.isFile() && candidate.canRead()) {
				mavenRequest.setLocalRepositoryPath(candidate);
			}
		}

		if (mavenRequest.getLocalRepositoryPath() == null) {
			mavenRequest.setLocalRepositoryPath(RepositorySystem.defaultUserLocalRepository);
		}

		RepositorySystem repositorySystem = container.lookup(RepositorySystem.class);
		ArtifactRepository localRepo = repositorySystem.createLocalRepository(mavenRequest.getLocalRepositoryPath());
		mavenRequest.setLocalRepository(localRepo);
		List<ArtifactRepository> defaultRemoteRepositories = Collections.singletonList(repositorySystem.createDefaultRemoteRepository());
		mavenRequest.setRemoteRepositories(joinRemoteRepositories(mavenRequest.getRemoteRepositories(), defaultRemoteRepositories));
		mavenRequest.setPluginArtifactRepositories(joinRemoteRepositories(mavenRequest.getPluginArtifactRepositories(), defaultRemoteRepositories));
		mavenRequest.setSystemProperties(System.getProperties());
		mavenRequest.setCacheNotFound(true);
		mavenRequest.setCacheTransferError(true);
		mavenRequest.setWorkspaceReader(new MavenLemminxWorkspaceReader(this));
		return mavenRequest;
	}
	
	private List<ArtifactRepository> joinRemoteRepositories(List<ArtifactRepository> a, List<ArtifactRepository> b) {
	  if (a.isEmpty()) {
	    return b;
	  }
	  List<ArtifactRepository> remotes = new ArrayList<>(a);
	  remotes.addAll(b);
	  return remotes;	  
	}

	private static File getFileFromOptions(String element, File defaults) {
		if (element == null) {
			return defaults;
		}
		if (!element.trim().isEmpty()) {
			File globalSettingsCandidate = new File(element);
			if (globalSettingsCandidate.canRead()) {
				return globalSettingsCandidate;
			}
		}
		return defaults;
	}

	/* Copied from m2e */
	private DefaultPlexusContainer newPlexusContainer() throws PlexusContainerException {
		final ClassWorld classWorld = new ClassWorld(MAVEN_XMLLS_EXTENSION_REALM_ID, ClassWorld.class.getClassLoader());
		final ClassRealm realm;
		try {
			realm = classWorld.getRealm(MAVEN_XMLLS_EXTENSION_REALM_ID);
		} catch (NoSuchRealmException e) {
			throw new PlexusContainerException("Could not lookup required class realm", e);
		}
		final ContainerConfiguration mavenCoreCC = new DefaultContainerConfiguration() //
				.setClassWorld(classWorld) //
				.setRealm(realm) //
				.setClassPathScanning(PlexusConstants.SCANNING_INDEX) //
				.setAutoWiring(true) //
				.setName("mavenCore"); //$NON-NLS-1$

		// final Module logginModule = new AbstractModule() {
		// protected void configure() {
		// bind(ILoggerFactory.class).toInstance(LoggerFactory.getILoggerFactory());
		// }
		// };
		// final Module coreExportsModule = new AbstractModule() {
		// protected void configure() {
		// ClassRealm realm = mavenCoreCC.getRealm();
		// CoreExtensionEntry entry = CoreExtensionEntry.discoverFrom(realm);
		// CoreExports exports = new CoreExports(entry);
		// bind(CoreExports.class).toInstance(exports);
		// }
		// };
		return new DefaultPlexusContainer(mavenCoreCC);
	}

	@Override
	public void stop(XMLExtensionsRegistry registry) {
		registry.unregisterCompletionParticipant(completionParticipant);
		this.completionParticipant = null;
		registry.unregisterDiagnosticsParticipant(diagnosticParticipant);
		this.diagnosticParticipant = null;
		registry.unregisterHoverParticipant(hoverParticipant);
		this.hoverParticipant = null;
		registry.unregisterDefinitionParticipant(definitionParticipant);
		this.definitionParticipant = null;
		if (localRepositorySearcher != null) {
			localRepositorySearcher.stop();
			localRepositorySearcher = null;
		}
		if (indexSearcher != null) {
			indexSearcher.closeContext();
			indexSearcher = null;
		}
		cache = null;
		if (container != null) {
			container.dispose();
			container = null;
		}
		this.mavenSession = null;
		this.currentRegistry = null;
	}

	public static boolean match(DOMDocument document) {
		try {
			return match(new File(URI.create(document.getDocumentURI())).toPath());
		} catch (Exception ex) {
			// usually because of not so tolerant Java URI API
			LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
			return false;
		}
	}
	
	public static boolean match(Path file) {
		String fileName = file != null ? file.getFileName().toString() : null;
		return fileName != null && ((fileName.startsWith("pom") && fileName.endsWith(".xml"))
				|| fileName.endsWith(Maven.POMv4));
	}
	
	public MavenProjectCache getProjectCache() {
		initialize();
		return this.cache;
	}

	public MavenSession getMavenSession() {
		initialize();
		return this.mavenSession;
	}

	public PlexusContainer getPlexusContainer() {
		initialize();
		return this.container;
	}

	public BuildPluginManager getBuildPluginManager() {
		initialize();
		return buildPluginManager;
	}

	public LocalRepositorySearcher getLocalRepositorySearcher() {
		initialize();
		return localRepositorySearcher;
	}

	public MavenPluginManager getMavenPluginManager() {
		initialize();
		return mavenPluginManager;
	}
	
	public Optional<RemoteRepositoryIndexSearcher> getIndexSearcher() {
		initialize();
		return Optional.ofNullable(indexSearcher);
	}

	public URIResolverExtensionManager getUriResolveExtentionManager() {
		initialize();
		return resolverExtensionManager;
	}
	
	public void didChangeWorkspaceFolders(URI[] added, URI[] removed) {
		initialize();
		WorkspaceReader workspaceReader = mavenRequest.getWorkspaceReader();
		if (workspaceReader instanceof MavenLemminxWorkspaceReader) {
			Collection<URI> projectsToAdd = computeAddedWorkspaceProjects(added != null? added : new URI[0]);
			Collection<URI> projectsToRemove = computeRemovedWorkspaceProjects(removed != null ? removed : new URI[0]);

			// Optimize: for workspace folder and MavenWorkspaceReader, we don't need to keep the whole Maven project
			// Also, we could add multiple document at once to share the common parents and so on.
			if (projectsToAdd != null) {
				projectsToAdd.stream().forEach(u -> getProjectCache().addDocument(u));
			}

			if (projectsToRemove != null) {
				projectsToRemove.stream().forEach(u -> getProjectCache().removeDocument(u));
			}
		}
	}
	
	private Collection<URI> computeRemovedWorkspaceProjects(URI[] removed) {
		Collection<MavenProject> cachedProjects = getProjectCache().getProjects();
		return cachedProjects.stream().filter(p -> {
			return Arrays.stream(removed).anyMatch(uri -> {
				Path removedPath = new File(uri).toPath();
				return p.getFile().toPath().startsWith(removedPath);
			});
		}).map(p -> p.getFile().toURI()).collect(Collectors.toUnmodifiableList());
	}
	
	private List<URI> computeAddedWorkspaceProjects(URI[] added) {
		List<URI> projectsToAdd = new ArrayList<>();
		Arrays.asList(added).stream().forEach(uri -> {
			Path addedPath = new File(uri).toPath();
			try {
				Files.walkFileTree(addedPath, Collections.emptySet(), 10, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult preVisitDirectory(Path file, BasicFileAttributes attrs) throws IOException {
						if (file.getFileName().toString().charAt(0) == '.') {
							return FileVisitResult.SKIP_SUBTREE;
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (match(file)) {
							projectsToAdd.add(file.toUri());
						}
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
			}
		});
		
		return projectsToAdd;
	}
}
