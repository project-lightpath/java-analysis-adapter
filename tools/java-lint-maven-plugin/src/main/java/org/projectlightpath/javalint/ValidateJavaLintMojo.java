package org.projectlightpath.javalint;

import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Maven goal that enforces the reviewed PMD/CPD baseline for a project. */
@Mojo(name = "validate", requiresProject = true, threadSafe = true)
public final class ValidateJavaLintMojo extends AbstractMojo {
  /** Project root containing config/, target/, and src/. */
  @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
  private File baseDirectory;

  /** Whether new PMD/CPD findings should fail the goal. */
  @Parameter(property = "javaLint.enforce", defaultValue = "true")
  private boolean enforce;

  /** Loads the generated reports and fails when the reviewed lint contract is violated. */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      JavaLintValidator.ValidationReport report =
          new JavaLintValidator(baseDirectory.toPath()).validate(enforce);
      report.messages().forEach(message -> getLog().info(message));
      if (!report.passed()) {
        throw new MojoFailureException(report.failureMessage());
      }
    } catch (MojoFailureException error) {
      throw error;
    } catch (Exception error) {
      throw new MojoExecutionException("Java lint report validation failed", error);
    }
  }
}
