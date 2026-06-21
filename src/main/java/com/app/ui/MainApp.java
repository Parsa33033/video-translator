package com.app.ui;

import com.app.core.pipeline.PipelineRequest;
import com.app.core.pipeline.PipelineResult;
import com.app.core.pipeline.ProgressListener;
import com.app.core.pipeline.TranscriptionPipeline;
import com.app.ffmpeg.FFmpegException;
import com.app.ffmpeg.FFmpegRunner;
import com.app.model.PipelinePhase;
import com.app.model.SourceLanguage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.prefs.Preferences;

public class MainApp extends Application {

    private static final String PREF_API_KEY = "geminiApiKey";
    private static final String PREF_OUTPUT_DIR = "outputDir";
    private static final String PREF_BURN = "burnSubtitles";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Preferences prefs = Preferences.userNodeForPackage(MainApp.class);

    private final SimpleStringProperty inputPath = new SimpleStringProperty("");
    private final SimpleStringProperty outputPath = new SimpleStringProperty("");

    private TextField inputField;
    private TextField outputField;
    private PasswordField apiKeyField;
    private ChoiceBox<SourceLanguage> languageChoice;
    private CheckBox burnCheckbox;
    private Label outputLabel;
    private Button transcribeButton;
    private Button openOutputButton;
    private Button playButton;
    private ProgressBar progressBar;
    private Label phaseLabel;
    private TextArea logArea;
    private MediaView mediaView;
    private MediaPlayer mediaPlayer;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Video Transcriber — Translate & Subtitle");
        stage.setScene(new Scene(buildRoot(stage), 980, 720));
        stage.show();
        Platform.runLater(this::checkFfmpeg);
    }

    private VBox buildRoot(Stage stage) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        Label title = new Label("Video Transcriber");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);

        form.add(new Label("Video file:"), 0, 0);
        inputField = new TextField();
        inputField.textProperty().bindBidirectional(inputPath);
        inputField.setEditable(false);
        Button browseInput = new Button("Browse...");
        browseInput.setOnAction(e -> chooseInput(stage));
        HBox inputBox = new HBox(8, inputField, browseInput);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        form.add(inputBox, 1, 0);

        form.add(new Label("Source language:"), 0, 1);
        languageChoice = new ChoiceBox<>();
        languageChoice.getItems().addAll(SourceLanguage.values());
        languageChoice.getSelectionModel().select(SourceLanguage.FRENCH);
        form.add(languageChoice, 1, 1);

        form.add(new Label("Gemini API key:"), 0, 2);
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("AIza...");
        apiKeyField.setText(prefs.get(PREF_API_KEY, ""));
        form.add(apiKeyField, 1, 2);

        outputLabel = new Label("Output video:");
        form.add(outputLabel, 0, 3);
        outputField = new TextField();
        outputField.textProperty().bindBidirectional(outputPath);
        outputField.setEditable(false);
        Button browseOutput = new Button("Browse...");
        browseOutput.setOnAction(e -> chooseOutput(stage));
        HBox outputBox = new HBox(8, outputField, browseOutput);
        HBox.setHgrow(outputField, Priority.ALWAYS);
        form.add(outputBox, 1, 3);

        burnCheckbox = new CheckBox("Burn subtitles into video");
        burnCheckbox.setSelected(prefs.getBoolean(PREF_BURN, true));
        burnCheckbox.selectedProperty().addListener((obs, was, now) -> {
            applyBurnMode(now);
            prefs.putBoolean(PREF_BURN, now);
        });
        form.add(burnCheckbox, 1, 4);
        applyBurnMode(burnCheckbox.isSelected());

        transcribeButton = new Button("Transcribe & Translate");
        transcribeButton.setDefaultButton(true);
        transcribeButton.setOnAction(e -> startPipeline());

        phaseLabel = new Label("Idle");
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        VBox progressBox = new VBox(4, phaseLabel, progressBar);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(10);
        logArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 11px;");

        mediaView = new MediaView();
        mediaView.setPreserveRatio(true);
        mediaView.setFitHeight(260);

        playButton = new Button("Play preview");
        playButton.setDisable(true);
        playButton.setOnAction(e -> togglePlayback());

        openOutputButton = new Button("Open output folder");
        openOutputButton.setDisable(true);
        openOutputButton.setOnAction(e -> openOutputFolder());

        HBox actions = new HBox(10, transcribeButton, playButton, openOutputButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox previewBox = new VBox(6, new Label("Preview"), mediaView);
        VBox logBox = new VBox(6, new Label("Logs"), logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        VBox.setVgrow(logBox, Priority.ALWAYS);

        root.getChildren().addAll(title, form, actions, progressBox, previewBox, logBox);
        return root;
    }

    private void checkFfmpeg() {
        try {
            new FFmpegRunner().verifyInstalled();
            appendLog("FFmpeg detected and ready.");
        } catch (FFmpegException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("FFmpeg missing");
            alert.setHeaderText("FFmpeg is required but was not found");
            alert.setContentText(ex.getMessage()
                    + "\n\nInstall FFmpeg (https://ffmpeg.org/download.html) and make sure "
                    + "both 'ffmpeg' and 'ffprobe' are on your PATH, then restart this app.");
            alert.getButtonTypes().setAll(ButtonType.CLOSE);
            alert.showAndWait();
            transcribeButton.setDisable(true);
        }
    }

    private void applyBurnMode(boolean burnOn) {
        outputLabel.setText(burnOn ? "Output video:" : "Output SRT file:");
        String current = outputPath.get();
        if (current == null || current.isBlank()) return;
        String want = burnOn ? ".mp4" : ".srt";
        String other = burnOn ? ".srt" : ".mp4";
        if (current.toLowerCase().endsWith(other)) {
            outputPath.set(current.substring(0, current.length() - other.length()) + want);
        }
    }

    private void chooseInput(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select video to translate");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Videos",
                        "*.mp4", "*.m4v", "*.mov", "*.mkv", "*.webm",
                        "*.avi", "*.wmv", "*.flv", "*.ts", "*.mts", "*.m2ts",
                        "*.mpg", "*.mpeg", "*.3gp", "*.3g2", "*.ogv", "*.vob"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File f = chooser.showOpenDialog(stage);
        if (f != null) {
            inputPath.set(f.getAbsolutePath());
            if (outputPath.get().isEmpty()) {
                String dir = prefs.get(PREF_OUTPUT_DIR, f.getParent());
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = burnCheckbox.isSelected() ? (dot > 0 ? name.substring(dot) : ".mp4") : ".srt";
                outputPath.set(Paths.get(dir, base + "_en" + ext).toString());
            }
            appendLog("Selected input: " + f.getAbsolutePath());
        }
    }

    private void chooseOutput(Stage stage) {
        boolean burnOn = burnCheckbox.isSelected();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(burnOn ? "Save subtitled video as" : "Save subtitles as");
        chooser.getExtensionFilters().add(burnOn
                ? new FileChooser.ExtensionFilter("MP4 video", "*.mp4")
                : new FileChooser.ExtensionFilter("SRT subtitles", "*.srt"));
        if (!outputPath.get().isEmpty()) {
            File current = new File(outputPath.get());
            if (current.getParentFile() != null && current.getParentFile().exists()) {
                chooser.setInitialDirectory(current.getParentFile());
            }
            chooser.setInitialFileName(current.getName());
        }
        File f = chooser.showSaveDialog(stage);
        if (f != null) {
            outputPath.set(f.getAbsolutePath());
            if (f.getParentFile() != null) {
                prefs.put(PREF_OUTPUT_DIR, f.getParentFile().getAbsolutePath());
            }
        }
    }

    private void startPipeline() {
        String input = inputPath.get();
        String output = outputPath.get();
        String apiKey = apiKeyField.getText();
        SourceLanguage lang = languageChoice.getValue();

        if (input == null || input.isBlank() || !Files.exists(Path.of(input))) {
            warn("Please pick a video file first.");
            return;
        }
        if (output == null || output.isBlank()) {
            warn("Please pick an output path.");
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            warn("Please enter your Gemini API key.");
            return;
        }
        prefs.put(PREF_API_KEY, apiKey);

        PipelineRequest req = new PipelineRequest(
                Path.of(input),
                Path.of(output),
                lang,
                apiKey,
                burnCheckbox.isSelected(),
                false
        );

        transcribeButton.setDisable(true);
        openOutputButton.setDisable(true);
        playButton.setDisable(true);
        progressBar.setProgress(0);
        phaseLabel.setText("Starting...");
        logArea.clear();

        Task<PipelineResult> task = new Task<>() {
            @Override
            protected PipelineResult call() {
                TranscriptionPipeline pipeline = new TranscriptionPipeline();
                ProgressListener listener = new ProgressListener() {
                    @Override public void onPhase(PipelinePhase phase) {
                        Platform.runLater(() -> phaseLabel.setText(phase.label()));
                    }
                    @Override public void onProgress(double overallFraction) {
                        Platform.runLater(() -> progressBar.setProgress(overallFraction));
                    }
                    @Override public void onLog(String line) {
                        Platform.runLater(() -> appendLog(line));
                    }
                };
                try {
                    return pipeline.run(req, listener);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        task.setOnSucceeded(e -> {
            PipelineResult result = task.getValue();
            phaseLabel.setText("Done");
            progressBar.setProgress(1.0);
            transcribeButton.setDisable(false);
            openOutputButton.setDisable(false);
            if (result.outputVideo() != null) {
                loadPreview(result.outputVideo());
            } else {
                playButton.setDisable(true);
                appendLog("SRT written to: " + result.srtFile() + " (no video render — burn is off)");
            }
        });
        task.setOnFailed(e -> {
            Throwable err = task.getException();
            phaseLabel.setText("Failed");
            transcribeButton.setDisable(false);
            String msg = err == null ? "Unknown error" : err.getMessage();
            appendLog("ERROR: " + msg);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Pipeline failed");
            alert.setHeaderText("Translation pipeline failed");
            alert.setContentText(msg);
            alert.showAndWait();
        });

        Thread t = new Thread(task, "pipeline");
        t.setDaemon(true);
        t.start();
    }

    private void loadPreview(Path video) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.dispose();
                mediaPlayer = null;
            }
            Media media = new Media(video.toUri().toString());
            mediaPlayer = new MediaPlayer(media);
            mediaView.setMediaPlayer(mediaPlayer);
            playButton.setDisable(false);
            playButton.setText("Play preview");
            appendLog("Preview ready: " + video);
        } catch (Exception ex) {
            appendLog("Could not load preview: " + ex.getMessage()
                    + " (JavaFX media supports a limited set of codecs)");
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null) return;
        MediaPlayer.Status status = mediaPlayer.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
            playButton.setText("Play preview");
        } else {
            mediaPlayer.play();
            playButton.setText("Pause");
        }
    }

    private void openOutputFolder() {
        String out = outputPath.get();
        if (out == null || out.isBlank()) return;
        File f = new File(out);
        File dir = f.getParentFile();
        if (dir == null || !dir.exists()) return;
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception e) {
            appendLog("Could not open folder: " + e.getMessage());
        }
    }

    private void warn(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Missing input");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void appendLog(String line) {
        logArea.appendText("[" + LocalTime.now().format(LOG_TIME) + "] " + line + "\n");
    }

    @Override
    public void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.dispose();
        }
    }
}
