package io.kestra.plugin.scripts.r;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.runners.FilesService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.AbstractExecScript;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute an R script."
)
@Plugin(
    examples = {
        @Example(
            title = "Install a package and execute an R script",
            code = {
                "script: |",
                "  library(lubridate)",
                "  ymd(\"20100604\");",
                "  mdy(\"06-04-2011\");",
                "  dmy(\"04/06/2012\")",
                "beforeCommands:",
                "  - Rscript -e 'install.packages(\"lubridate\")'"
            }
        ),
        @Example(
            full = true,
            title = """
            Add an R script in the embedded VS Code editor, install required packages and execute it. 
            
            Here is an example R script that you can add in the embedded VS Code editor. You can name the script file `main.R`:
            
            ```r
            library(dplyr)
            library(arrow)

            data(mtcars) # load mtcars data
            print(head(mtcars))

            final <- mtcars %>%
                summarise(
                avg_mpg = mean(mpg),
                avg_disp = mean(disp),
                avg_hp = mean(hp),
                avg_drat = mean(drat),
                avg_wt = mean(wt),
                avg_qsec = mean(qsec),
                avg_vs = mean(vs),
                avg_am = mean(am),
                avg_gear = mean(gear),
                avg_carb = mean(carb)
                ) 
            final %>% print()
            write.csv(final, "final.csv")

            mtcars_clean <- na.omit(mtcars) # this line removes rows with NA values
            write_parquet(mtcars_clean, "mtcars_clean.parquet")
            ```        

            Note that tasks in Kestra are stateless. Therefore, the files generated by a task, such as the CSV and Parquet files in the example above, are not persisted in Kestra's internal storage, unless you explicitly tell Kestra to do so. Make sure to add the `outputFiles` property to your task as shown below to persist the generated Parquet file (or any other file) in Kestra's internal storage and make them visible in the **Outputs** tab.

            To access this output in downstream tasks, use the syntax `{{outputs.yourTaskId.outputFiles['yourFileName.fileExtension']}}`. Alternatively, you can wrap your tasks that need to pass data between each other in a `WorkingDirectory` task — this way, those tasks will share the same working directory and will be able to access the same files.

            Note how we use the `read` function to read the content of the R script stored as a [Namespace File](https://kestra.io/docs/developer-guide/namespace-files).

            Finally, note that the `docker` property is optional. If you don't specify it, Kestra will use the default R image. If you want to use a different image, you can specify it in the `docker` property as shown below.
            """,
            code = """
                id: rCars
                namespace: dev
                tasks:
                  - id: r
                    type: io.kestra.plugin.scripts.r.Script
                    warningOnStdErr: false
                    containerImage: ghcr.io/kestra-io/rdata:latest
                    script: "{{ read('main.R') }}"
                    outputFiles:
                      - "*.csv"
                      - "*.parquet"
                """
        )        
    }
)
public class Script extends AbstractExecScript {
    private static final String DEFAULT_IMAGE = "r-base";

    @Builder.Default
    protected String containerImage = DEFAULT_IMAGE;

    @Schema(
        title = "The inline script content. This property is intended for the script file's content as a (multiline) string, not a path to a file. To run a command from a file such as `Rscript main.R` or `python main.py`, use the corresponding `Commands` task for a given language instead."
    )
    @PluginProperty(dynamic = true)
    @NotNull
    @NotEmpty
    protected String script;

    @Override
    protected DockerOptions injectDefaults(DockerOptions original) {
        var builder = original.toBuilder();
        if (original.getImage() == null) {
            builder.image(DEFAULT_IMAGE);
        }

        return builder.build();
    }

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        CommandsWrapper commands = this.commands(runContext);

        Map<String, String> inputFiles = FilesService.inputFiles(runContext, commands.getTaskRunner().additionalVars(runContext, commands), this.getInputFiles());
        List<String> internalToLocalFiles = new ArrayList<>();
        Path relativeScriptPath = runContext.tempDir().relativize(runContext.tempFile(".R"));
        inputFiles.put(
            relativeScriptPath.toString(),
            commands.render(runContext, this.script, internalToLocalFiles)
        );
        commands = commands.withInputFiles(inputFiles);

        List<String> commandsArgs = ScriptService.scriptCommands(
            this.interpreter,
            getBeforeCommandsWithOptions(),
            String.join(" ", "Rscript", commands.getTaskRunner().toAbsolutePath(runContext, commands, relativeScriptPath.toString(), this.targetOS)),
            this.targetOS
        );

        return commands
            .withCommands(commandsArgs)
            .run();
    }
}
