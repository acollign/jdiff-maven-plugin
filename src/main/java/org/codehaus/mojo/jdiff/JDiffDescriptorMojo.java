package org.codehaus.mojo.jdiff;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.SystemUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;

/**
 * Generates an API descriptor of the Java sources.
 *
 * @goal descriptor
 * @execute phase="generate-sources"
 * @requiresDependencyResolution compile
 */
public class JDiffDescriptorMojo extends AbstractMojo {

	/**
	 * The Javadoc executable.
	 *
	 * @parameter expression="${javadocExecutable}"
	 */
	private String javadocExecutable;

	/**
	 * The JDiff API name.
	 *
	 * @parameter default-value="${project.name}-${project.version}"
	 */
	private String apiname;

	/**
	 * The output directory.
	 *
	 * @parameter default-value="${project.build.outputDirectory}"
	 * @required
	 * @readonly
	 */
	private String buildOutputDirectory;

	/**
	 * The working directory for this plugin.
	 *
	 * @parameter default-value="${project.build.directory}/jdiff"
	 * @readonly
	 */
	private File workingDirectory;

	/**
	 * The name of the destination directory.
	 *
	 * @parameter expression="${destDir}" default-value="apidocs"
	 */
	private String destDir;

	/**
	 * The current build session instance. This is used for toolchain manager
	 * API calls.
	 *
	 * @parameter expression="${session}"
	 * @required
	 * @readonly
	 */
	private MavenSession session;

	/**
	 * The Maven project.
	 *
	 * @parameter default-value="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/**
	 * The {@link ToolchainManager}.
	 *
	 * @component
	 */
	private ToolchainManager toolchainManager;

	/**
	 * Artifacts.
	 *
	 * @parameter default-value="${plugin.artifacts}"
	 * @required
	 * @readonly
	 */
	private List<Artifact> pluginArtifacts;

	/**
	 * List of packages to include separated by space.
	 *
	 * @parameter expression="${includePackageNames}"
	 */
	private String includePackageNames;


	/**
	 * Holds the packages of both the comparisonVersion and baseVersion
	 */
	private final Set<String> packages = new HashSet<String>();

	/**
	 * {@inheritDoc}
	 *
	 * @see org.apache.maven.plugin.AbstractMojo#execute()
	 */
	public void execute() throws MojoExecutionException {
		try {
			generateJDiffXML(apiname);
		} catch (JavadocExecutionException e) {
			getLog().error("Error when generating the JDiff descriptor");
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	/**
	 * Generates the JDiff XML descriptor.
	 *
	 * @param apiname
	 *            the api name used as a filename
	 * @throws JavadocExecutionException
	 *             thrown if an error occurred during the process
	 */
	private void generateJDiffXML(String apiname)
			throws JavadocExecutionException {
		try {
			JavadocExecutor javadoc = new JavadocExecutor(
					getJavadocExecutable(), getLog());

			javadoc.addArgumentPair("doclet", "jdiff.JDiff");
			javadoc.addArgumentPair("docletpath", getPluginClasspath());
			javadoc.addArgumentPair("apiname", apiname);
			javadoc.addArgumentPair("apidir",
					workingDirectory.getAbsolutePath());

			List<String> classpathElements = new ArrayList<String>();
			classpathElements.add(buildOutputDirectory);
			classpathElements.addAll(JDiffUtils.getClasspathElements(project));
			String classpath = StringUtils.join(classpathElements.iterator(),
					File.pathSeparator);
			javadoc.addArgumentPair("classpath",
					StringUtils.quoteAndEscape(classpath, '\''));

			String sourcePath = StringUtils.join(JDiffUtils
					.getProjectSourceRoots(project).iterator(),
					File.pathSeparator);
			javadoc.addArgumentPair("sourcepath",
					StringUtils.quoteAndEscape(sourcePath, '\''));

			Set<String> pckgs = new TreeSet<String>();

			if(includePackageNames != null && !includePackageNames.isEmpty()) {
				List<String> names = Arrays.asList(includePackageNames.split(" "));

				getLog().debug("Included packages (overwritten by [includePackageNames] parameter) : " + names);

				pckgs.addAll(names);
			} else {
				pckgs = JDiffUtils.getPackages(project);
			}

			for (String pckg : pckgs) {
				javadoc.addArgument(pckg);
			}
			packages.addAll(pckgs);

			javadoc.execute(workingDirectory.getAbsolutePath());
		} catch (IOException e) {
			throw new JavadocExecutionException(e.getMessage(), e);
		}
	}

	/**
	 * Return the plugin classpath.
	 *
	 * @return the plugin classpath
	 */
	private String getPluginClasspath() {
		String cp = "";
		for (Artifact artifact : pluginArtifacts) {
			cp += File.pathSeparatorChar + artifact.getFile().getAbsolutePath();
		}

		return cp.length() > 0 ? cp.substring(1) : cp;
	}

	/**
	 * Returns the {@link ToolchainManager}.
	 *
	 * @return the {@link ToolchainManager}
	 */
	private Toolchain getToolchain() {
		return toolchainManager.getToolchainFromBuildContext("jdk", session);
	}

	// Borrowed from maven-javadoc-plugin
	/**
	 * Get the path of the Javadoc tool executable depending the user entry or
	 * try to find it depending the OS or the <code>java.home</code> system
	 * property or the <code>JAVA_HOME</code> environment variable.
	 *
	 * @return the path of the Javadoc tool
	 * @throws IOException
	 *             if not found
	 */
	private String getJavadocExecutable() throws IOException {
		Toolchain tc = getToolchain();

		if (tc != null) {
			getLog().info("Toolchain in javadoc-plugin: " + tc);
			if (javadocExecutable != null) {
				getLog().warn(
						"Toolchains are ignored, 'javadocExecutable' parameter is set to "
								+ javadocExecutable);
			} else {
				javadocExecutable = tc.findTool("javadoc");
			}
		}

		String javadocCommand = "javadoc"
				+ (SystemUtils.IS_OS_WINDOWS ? ".exe" : "");

		File javadocExe;

		// ----------------------------------------------------------------------
		// The javadoc executable is defined by the user
		// ----------------------------------------------------------------------
		if (StringUtils.isNotEmpty(javadocExecutable)) {
			javadocExe = new File(javadocExecutable);

			if (javadocExe.isDirectory()) {
				javadocExe = new File(javadocExe, javadocCommand);
			}

			if (SystemUtils.IS_OS_WINDOWS
					&& javadocExe.getName().indexOf('.') < 0) {
				javadocExe = new File(javadocExe.getPath() + ".exe");
			}

			if (!javadocExe.isFile()) {
				throw new IOException(
						"The javadoc executable '"
								+ javadocExe
								+ "' doesn't exist or is not a file. Verify the <javadocExecutable/> parameter.");
			}

			return javadocExe.getAbsolutePath();
		}

		// ----------------------------------------------------------------------
		// Try to find javadocExe from System.getProperty( "java.home" )
		// By default, System.getProperty( "java.home" ) = JRE_HOME and JRE_HOME
		// should be in the JDK_HOME
		// ----------------------------------------------------------------------
		// For IBM's JDK 1.2
		if (SystemUtils.IS_OS_AIX) {
			javadocExe = new File(SystemUtils.getJavaHome() + File.separator
					+ ".." + File.separator + "sh", javadocCommand);
		} else if (SystemUtils.IS_OS_MAC_OSX) {
			javadocExe = new File(SystemUtils.getJavaHome() + File.separator
					+ "bin", javadocCommand);
		} else {
			javadocExe = new File(SystemUtils.getJavaHome() + File.separator
					+ ".." + File.separator + "bin", javadocCommand);
		}

		// ----------------------------------------------------------------------
		// Try to find javadocExe from JAVA_HOME environment variable
		// ----------------------------------------------------------------------
		if (!javadocExe.exists() || !javadocExe.isFile()) {
			Properties env = CommandLineUtils.getSystemEnvVars();
			String javaHome = env.getProperty("JAVA_HOME");
			if (StringUtils.isEmpty(javaHome)) {
				throw new IOException(
						"The environment variable JAVA_HOME is not correctly set.");
			}
			if ((!new File(javaHome).exists())
					|| (!new File(javaHome).isDirectory())) {
				throw new IOException("The environment variable JAVA_HOME="
						+ javaHome
						+ " doesn't exist or is not a valid directory.");
			}

			javadocExe = new File(env.getProperty("JAVA_HOME") + File.separator
					+ "bin", javadocCommand);
		}

		if (!javadocExe.exists() || !javadocExe.isFile()) {
			throw new IOException(
					"The javadoc executable '"
							+ javadocExe
							+ "' doesn't exist or is not a file. Verify the JAVA_HOME environment variable.");
		}

		return javadocExe.getAbsolutePath();
	}
}