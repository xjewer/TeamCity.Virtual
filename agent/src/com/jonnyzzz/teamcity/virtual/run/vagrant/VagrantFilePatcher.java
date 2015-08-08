/*
 * Copyright 2000-2014 Eugene Petrenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jonnyzzz.teamcity.virtual.run.vagrant;

import com.jonnyzzz.teamcity.virtual.run.RelativePaths;
import com.jonnyzzz.teamcity.virtual.util.util.BuildProcessBase;
import com.jonnyzzz.teamcity.virtual.util.util.TryFinallyBuildProcess;
import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.BuildFinishedStatus;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.FileUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

/**
 * @author Eugene Petrenko (eugene.petrenko@gmail.com)
 */
public class VagrantFilePatcher {

  public void generateVagrantFile(@NotNull final VagrantContext context,
                                  @NotNull final BuildProgressLogger logger,
                                  @NotNull final File originalVagrantFile,
                                  @NotNull final TryFinallyBuildProcess builder,
                                  @NotNull final WithGeneratedVagrantfile continuation) throws RunBuildException {

    builder.addTryProcess(new BuildProcessBase() {
      @NotNull
      @Override
      protected BuildFinishedStatus waitForImpl() throws RunBuildException {

        final String text;
        try {
          text = FileUtil.readText(originalVagrantFile);
        } catch (IOException e) {
          throw new RuntimeException("Failed to read original Vagranfile. " + e.getMessage(), e);
        }

        final File backupFile;
        try {
          backupFile = FileUtil.createTempFile(context.getAgentTempDirectory(), "Vagrantfile", ".before.patch", true);
        } catch (IOException e) {
          throw new RuntimeException("Failed to create backup for for Vagrantfile. " + e.getMessage(), e);
        }

        try {
          FileUtil.copy(originalVagrantFile, backupFile);
        } catch (IOException e) {
          throw new RuntimeException("Failed to create backup file at: " + backupFile + ". " + e.getMessage(), e);
        }

        builder.addFinishProcess(new BuildProcessBase() {
          @NotNull
          @Override
          protected BuildFinishedStatus waitForImpl() throws RunBuildException {
            try {
              FileUtil.copy(backupFile, originalVagrantFile);
            } catch (IOException e) {
              FileUtil.delete(originalVagrantFile);
              //NOP
            }
            logger.message("Restored original Vagrantfile");
            FileUtil.delete(backupFile);
            return BuildFinishedStatus.FINISHED_SUCCESS;
          }
        });

        final File mountRoot = context.getCheckoutDirectory();
        final String basePath = "/jonnyzzz";

        final String patch = generateVagrantfile(mountRoot, basePath);
        logger.activityStarted("generate", "Added to the end of the Vagrantfile", "vagrant");
        logger.message(patch);
        logger.activityFinished("generate", "vagrant");

        writeVagrantFile(originalVagrantFile, text + "\n\n" + patch);

        final String relPath = RelativePaths.resolveRelativePath(mountRoot, context.getWorkingDirectory());
        continuation.execute(basePath + (relPath.length() == 0 ? "" : "/" + relPath));
        return BuildFinishedStatus.FINISHED_SUCCESS;
      }

      private void writeVagrantFile(@NotNull final File vagrantFile,
                                    @NotNull final String text) throws RunBuildException {
        try {
          FileUtil.writeFileAndReportErrors(vagrantFile, text);
        } catch (IOException e) {
          throw new RunBuildException("Failed to create Vargantfile. " + e.getMessage(), e);
        }
      }
    });
  }

  @NotNull
  private static String generateVagrantfile(@NotNull final File mountRoot,
                                            @NotNull final String destRoot) {
    final String escapedPath = escapePath(mountRoot.getPath());
    return "## Generated by TeamCity.Virtual plugin\n" +
            "Vagrant.configure(\"2\") do |config|\n" +
            "  config.vm.synced_folder \"" + escapedPath + "\", \"" + destRoot + "\"\n" +
            "end\n";
  }

  @NotNull
  public static String escapePath(@NotNull final String path) {
    if (File.separatorChar == '\\') {
      return path.replace("\\", "\\\\");
    }
    return path;
  }

  public interface WithGeneratedVagrantfile {
    void execute(@NotNull final String machinePathToWork) throws RunBuildException;
  }

}
