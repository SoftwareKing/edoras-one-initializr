/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.edorasware.one.initializr.generator;

import com.edorasware.one.initializr.metadata.*;
import com.edorasware.one.initializr.util.Version;
import com.edorasware.one.initializr.util.VersionProperty;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A request to generate a project.
 *
 * @author Dave Syer
 * @author Stephane Nicoll
 */
public class ProjectRequest extends BasicProjectRequest {

	/**
	 * The id of the starter to use if no dependency is defined.
	 */
	public static final String DEFAULT_STARTER = "root_starter";

	private final Map<String, Object> parameters = new LinkedHashMap<>();

	// Resolved dependencies based on the ids provided by either "style" or "dependencies"
	private List<Dependency> resolvedDependencies;

	private final Map<String, BillOfMaterials> boms = new LinkedHashMap<>();

	private final Map<String, Repository> repositories = new LinkedHashMap<>();

	private final BuildProperties buildProperties = new BuildProperties();

	private List<String> facets = new ArrayList<>();
	private String build;

	public List<Dependency> getResolvedDependencies() {
		return resolvedDependencies;
	}

	public void setResolvedDependencies(List<Dependency> resolvedDependencies) {
		this.resolvedDependencies = resolvedDependencies;
	}

	public List<String> getFacets() {
		return facets;
	}

	public void setFacets(List<String> facets) {
		this.facets = facets;
	}

	public String getBuild() {
		return build;
	}

	public void setBuild(String build) {
		this.build = build;
	}

	/**
	 * Return the additional parameters that can be used to further identify the request.
	 */
	public Map<String, Object> getParameters() {
		return parameters;
	}

	public Map<String, BillOfMaterials> getBoms() {
		return boms;
	}

	public Map<String, Repository> getRepositories() {
		return repositories;
	}

	/**
	 * Return the build properties.
	 */
	public BuildProperties getBuildProperties() {
		return buildProperties;
	}

	/**
	 * Initializes this instance with the defaults defined in the specified
	 * {@link InitializrMetadata}.
	 */
	public void initialize(InitializrMetadata metadata) {
		BeanWrapperImpl bean = new BeanWrapperImpl(this);
		metadata.defaults().forEach((key, value) -> {
			if (bean.isWritableProperty(key)) {
				// We want to be able to infer a package name if none has been
				// explicitly set
				if (!key.equals("packageName")) {
					bean.setPropertyValue(key, value);
				}
			}
		});
	}

	/**
	 * Resolve this instance against the specified {@link InitializrMetadata}
	 */
	public void resolve(InitializrMetadata metadata) {
		List<String> depIds = !getStyle().isEmpty() ? getStyle() : getDependencies();
		String actualEdorasoneVersion = getEdorasoneVersion() != null ? getEdorasoneVersion()
				: metadata.getEdorasoneVersions().getDefault().getId();
		Version requestedVersion = Version.parse(actualEdorasoneVersion);
		this.resolvedDependencies = depIds.stream().map(it -> {
			Dependency dependency = metadata.getDependencies().get(it);
			if (dependency == null) {
				throw new InvalidProjectRequestException(
						"Unknown dependency '" + it + "' check project metadata");
			}
			return dependency.resolve(requestedVersion);
		}).collect(Collectors.toList());
		this.resolvedDependencies.forEach(it -> {
			it.getFacets().forEach(facet -> {
				if (!facets.contains(facet)) {
					facets.add(facet);
				}
			});
			if (!it.match(requestedVersion)) {
				throw new InvalidProjectRequestException(
						"Dependency '" + it.getId() + "' is not compatible "
								+ "with edora one " + requestedVersion);
			}
			if (it.getBom() != null) {
				resolveBom(metadata, it.getBom(), requestedVersion);
			}
			if (it.getRepository() != null) {
				String repositoryId = it.getRepository();
				this.repositories.computeIfAbsent(repositoryId, s -> metadata
						.getConfiguration().getEnv().getRepositories().get(s));
			}
		});
		if (getType() != null) {
			Type type = metadata.getTypes().get(getType());
			if (type == null) {
				throw new InvalidProjectRequestException(
						"Unknown type '" + getType() + "' check project metadata");
			}
			String buildTag = type.getTags().get("build");
			if (buildTag != null) {
				this.build = buildTag;
			}
		}
		if (getPackaging() != null) {
			DefaultMetadataElement packaging = metadata.getPackagings()
					.get(getPackaging());
			if (packaging == null) {
				throw new InvalidProjectRequestException("Unknown packaging '"
						+ getPackaging() + "' check project metadata");
			}
		}
		if (getLanguage() != null) {
			DefaultMetadataElement language = metadata.getLanguages().get(getLanguage());
			if (language == null) {
				throw new InvalidProjectRequestException("Unknown language '"
						+ getLanguage() + "' check project metadata");
			}
		}

		if (!StringUtils.hasText(getApplicationName())) {
			setApplicationName(
					metadata.getConfiguration().generateApplicationName(getName()));
		}
		setPackageName(metadata.getConfiguration().cleanPackageName(getPackageName(),
				metadata.getPackageName().getContent()));

		initializeRepositories(metadata, requestedVersion);

		initializeProperties(metadata);

		afterResolution(metadata);
	}

	/**
	 * Set the repositories that this instance should use based on the
	 * {@link InitializrMetadata} and the requested edoras one {@link Version}.
	 */
	protected void initializeRepositories(InitializrMetadata metadata,
			Version requestedVersion) {
		boms.values().forEach(it -> it.getRepositories().forEach(key -> {
			repositories.computeIfAbsent(key, s -> metadata.getConfiguration()
					.getEnv().getRepositories().get(s));
		}));
	}

	protected void initializeProperties(InitializrMetadata metadata) {
		if ("gradle".equals(build)) {
			buildProperties.getGradle().put("edorasoneVersion", this::getEdorasoneVersion);
			if ("kotlin".equals(getLanguage())) {
				buildProperties.getGradle().put("kotlinVersion", () -> metadata
						.getConfiguration().getEnv().getKotlin().getVersion());
			}
		}
		else {
			buildProperties.getMaven().put("project.build.sourceEncoding", () -> "UTF-8");
			buildProperties.getVersions().put(new VersionProperty("maven.compiler.source"),
					this::getJavaVersion);
			buildProperties.getVersions().put(new VersionProperty("maven.compiler.target"),
					this::getJavaVersion);
			buildProperties.getVersions().put(new VersionProperty("com.edorasware.one.starter.version"),
					this::getEdorasoneVersion);
			if ("kotlin".equals(getLanguage())) {
				buildProperties.getVersions().put(new VersionProperty("kotlin.version"),
						() -> metadata.getConfiguration().getEnv().getKotlin().getVersion());
				buildProperties.getMaven().put("kotlin.compiler.incremental", () -> "true");
			}
		}
	}

	private void resolveBom(InitializrMetadata metadata, String bomId,
			Version requestedVersion) {
		boms.computeIfAbsent(bomId, key -> {
			BillOfMaterials bom = metadata.getConfiguration().getEnv().getBoms().get(key)
					.resolve(requestedVersion);
			bom.getAdditionalBoms()
					.forEach(id -> resolveBom(metadata, id, requestedVersion));
			return bom;
		});
	}

	/**
	 * Update this request once it has been resolved with the specified
	 * {@link InitializrMetadata}.
	 */
	protected void afterResolution(InitializrMetadata metadata) {
		if ("war".equals(getPackaging())) {
			if (!hasWebFacet()) {
				// Need to be able to bootstrap the web app
//				resolvedDependencies.add(metadata.getDependencies().get("web"));
				facets.add("web");
			}
		}
		// Starter is statically in the template now
//		if (resolvedDependencies.stream().noneMatch(Dependency::isStarter)) {
//			// There"s no starter so we add the default one
//			addDefaultDependency();
//		}
	}

	/**
	 * Add a default dependency if the project does not define any dependency
	 */
	protected void addDefaultDependency() {
		Dependency root = new Dependency();
		root.setId(DEFAULT_STARTER);
		root.asEdorasoneStarter("");
		resolvedDependencies.add(root);
	}

	/**
	 * Specify if this request has the web facet enabled.
	 */
	public boolean hasWebFacet() {
		return hasFacet("web");
	}

	/**
	 * Specify if this request has the specified facet enabled
	 */
	public boolean hasFacet(String facet) {
		return facets.contains(facet);
	}

	@Override
	public String toString() {
		return "ProjectRequest [" + "parameters=" + parameters + ", "
				+ (resolvedDependencies != null
				? "resolvedDependencies=" + resolvedDependencies + ", "
				: "")
				+ "boms=" + boms + ", " + "repositories="
				+ repositories + ", " + "buildProperties="
				+ buildProperties + ", " + (facets != null
				? "facets=" + facets + ", " : "")
				+ (build != null ? "build=" + build : "") + "]";
	}

}
