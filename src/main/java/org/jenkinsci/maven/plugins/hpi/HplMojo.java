package org.jenkinsci.maven.plugins.hpi;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.archiver.jar.Manifest;
import org.codehaus.plexus.archiver.jar.Manifest.Attribute;
import org.codehaus.plexus.archiver.jar.Manifest.ExistingSection;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.codehaus.plexus.util.IOUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generate .hpl file.
 *
 * @author Kohsuke Kawaguchi
 * @goal hpl
 * @requiresDependencyResolution runtime
 */
public class HplMojo extends AbstractHpiMojo {
    /**
     * Path to <tt>$JENKINS_HOME</tt>. A .hpl file will be generated to this location.
     *
     * @parameter expression="${hudsonHome}
     */
    private File hudsonHome;

    public void setHudsonHome(File hudsonHome) {
        this.hudsonHome = hudsonHome;
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        if(!project.getPackaging().equals("hpi")) {
            getLog().info("Skipping "+project.getName()+" because it's not <packaging>hpi</packaging>");
            return;
        }

        File hplFile = computeHplFile();
        getLog().info("Generating "+hplFile);

        PrintWriter printWriter = null;
        try {
            Manifest mf = new Manifest();
            ExistingSection mainSection = mf.getMainSection();
            setAttributes(mainSection);

            // compute Libraries entry
            StringBuilder buf = new StringBuilder();
            buf.append(project.getBuild().getOutputDirectory());

            // we want resources to be picked up before target/classes,
            // so that the original (not in the copy) will be picked up first.
            for (Resource r : (List<Resource>) project.getBuild().getResources()) {
                if(new File(project.getBasedir(),r.getDirectory()).exists()) {
                    buf.append(',');
                    buf.append(r.getDirectory());
                }
            }

            buildLibraries(buf);

            mainSection.addAttributeAndCheck(new Attribute("Libraries",buf.toString()));

            // compute Resource-Path entry
            mainSection.addAttributeAndCheck(new Attribute("Resource-Path",warSourceDirectory.getAbsolutePath()));

            printWriter = new PrintWriter(new FileWriter(hplFile));
            mf.write(printWriter);
        } catch (ManifestException e) {
            throw new MojoExecutionException("Error preparing the manifest: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Error preparing the manifest: " + e.getMessage(), e);
        } finally {
            IOUtil.close(printWriter);
        }
    }

    /**
     * Compute library dependencies.
     *
     * <p>
     * The list produced by this function and the list of jars that the 'hpi' mojo
     * puts into WEB-INF/lib should be the same so that the plugins see consistent
     * environment.
     */
    private void buildLibraries(StringBuilder buf) throws IOException {
        Set<MavenArtifact> artifacts = getProjectArtfacts();

        // List up IDs of Jenkins plugin dependencies
        Set<String> jenkinsPlugins = new HashSet<String>();
        for (MavenArtifact artifact : artifacts) {
            if(artifact.isPlugin())
                jenkinsPlugins.add(artifact.getId());
        }

        OUTER:
        for (MavenArtifact artifact : artifacts) {
            if(jenkinsPlugins.contains(artifact.getId()))
                continue;   // plugin dependencies
            if(artifact.getDependencyTrail().size() >= 1 && jenkinsPlugins.contains(artifact.getDependencyTrail().get(1)))
                continue;   // no need to have transitive dependencies through plugins

            // if the dependency goes through jenkins core, that's not a library
            for (String trail : artifact.getDependencyTrail()) {
                if (trail.contains(":hudson-core:") || trail.contains(":jenkins-core:"))
                    continue OUTER;
            }

            ScopeArtifactFilter filter = new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME);
            if (!artifact.isOptional() && filter.include(artifact.artifact)) {
                buf.append(',');
                buf.append(artifact.getFile());
            }
        }
    }

    /**
     * Determine where to produce the .hpl file.
     */
    protected File computeHplFile() throws MojoExecutionException {
        if(hudsonHome==null) {
            throw new MojoExecutionException(
                "Property hudsonHome needs to be set to $HUDSON_HOME. Please use 'mvn -DhudsonHome=...' or" +
                "put <settings><profiles><profile><properties><property><hudsonHome>...</...>"
            );
        }

        File hplFile = new File(hudsonHome, "plugins/" + project.getBuild().getFinalName() + ".hpl");
        return hplFile;
    }
}
