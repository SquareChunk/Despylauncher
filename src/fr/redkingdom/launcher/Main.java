package fr.redkingdom.launcher;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Fenetre du launcher (interface Swing).
 *
 * OU MODIFIER QUOI :
 *  - Nom, couleur, adresse de mise a jour : bouton "Reglages" dans le launcher (pas besoin de toucher au code)
 *  - Valeurs par defaut ... DEFAULT_TITLE / DEFAULT_ACCENT / DEFAULT_UPDATE_URL ci-dessous
 *  - Autres couleurs ...... BG / PANEL / LINE / TXT / MUT
 *  - Textes et boutons .... methode show()
 *  - Logique de lancement . Core.java
 */
public class Main {
    // ---- valeurs par defaut (modifiables aussi depuis le bouton Reglages) ----
    static final String DEFAULT_TITLE = "MClauncher";
    static final int DEFAULT_ACCENT = 0xE52D4D;
    /** Adresse directe du .jar en ligne (ex: https://raw.githubusercontent.com/PSEUDO/DEPOT/main/Despylauncher.jar). Vide = pas de mise a jour. */
    static final String DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/SquareChunk/Despylauncher/main/Despylauncher.jar";

    static final Color BG = new Color(0x141A1A);
    static final Color PANEL = new Color(0x1D2626);
    static final Color LINE = new Color(0x2C3A3A);
    static final Color TXT = new Color(0xE8EEEE);
    static final Color MUT = new Color(0x8B9BA1);
    static Color ACC = new Color(DEFAULT_ACCENT);
    static String title = DEFAULT_TITLE;

    private final Core core;
    private final Path settingsFile;
    private final JTextField user = new JTextField(16);
    private final JComboBox<Core.Entry> versions = new JComboBox<>();
    private final JCheckBox snapshots = new JCheckBox("Snapshots");
    private final JSpinner ram = new JSpinner(new SpinnerNumberModel(2048, 512, 32768, 512));
    private final JTextField java = new JTextField(16);
    private final JTextField server = new JTextField(16);
    private final JButton play = new JButton("JOUER");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel("");
    private final List<Core.Entry> all = new ArrayList<>();
    private String wantedVersion = "";
    private final JCheckBox closeOnLaunch = new JCheckBox("Fermer le launcher au lancement du jeu");
    private final JCheckBox fullscreen = new JCheckBox("Plein écran");
    private final JButton stop = btn("Arrêter le jeu");
    private volatile Process running;
    private JFrame frame;
    private JLabel titleLabel;

    // reglages du launcher lui-meme
    private String updateUrl = DEFAULT_UPDATE_URL;
    private String updateTag = ""; // ancien systeme (ETag), garde seulement pour la compatibilite des reglages

    /** Numero de version de CE launcher : lu dans version.txt, embarque dans le .jar par build.bat. */
    static final int VERSION = readLocalVersion();

    private static int readLocalVersion() {
        try (var in = Main.class.getResourceAsStream("/version.txt")) {
            if (in == null) return 0;
            return Integer.parseInt(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ demarrage

    /** Identifiant des valeurs par defaut du code : change des que tu modifies le nom, la couleur ou le lien de mise a jour. */
    static String defaultsId() {
        return DEFAULT_TITLE + "|" + DEFAULT_ACCENT + "|" + DEFAULT_UPDATE_URL;
    }

    static boolean sameDefaults(Map<String, Object> m) {
        return defaultsId().equals(m.get("defaultsId"));
    }

    static Path dataDir() {
        String custom = System.getProperty("rl.dir");
        String home = System.getProperty("user.home");
        if (custom != null) return Path.of(custom);
        if (Core.OS.equals("windows") && System.getenv("APPDATA") != null) return Path.of(System.getenv("APPDATA"), ".Despylauncher");
        if (Core.OS.equals("osx")) return Path.of(home, "Library", "Application Support", "Despylauncher");
        return Path.of(home, ".redlauncher");
    }

    private static Map<String, Object> readSettings(Path file) {
        try {
            if (Files.exists(file)) return Core.obj(Json.parse(Files.readString(file)));
        } catch (Exception e) {
            // illisible : valeurs par defaut
        }
        return new LinkedHashMap<>();
    }

    public static void main(String[] args) {
        Map<String, Object> m = readSettings(dataDir().resolve("launcher.json"));
        // nom / couleur sauvegardes : gardes seulement si les valeurs par defaut du code n'ont pas change
        // (sinon une mise a jour s'applique a tout le monde)
        boolean same = sameDefaults(m);
        if (same && m.get("title") instanceof String s && !s.isBlank()) title = s.trim();
        if (same && m.get("accent") instanceof Number n) ACC = new Color(n.intValue());
        applyTheme();
        SwingUtilities.invokeLater(() -> new Main().show());
    }

    private static void applyTheme() {
        UIManager.put("Panel.background", BG);
        UIManager.put("Label.foreground", TXT);
        UIManager.put("TextField.background", PANEL);
        UIManager.put("TextField.foreground", TXT);
        UIManager.put("TextField.caretForeground", TXT);
        UIManager.put("FormattedTextField.background", PANEL);
        UIManager.put("FormattedTextField.foreground", TXT);
        UIManager.put("ComboBox.background", PANEL);
        UIManager.put("ComboBox.foreground", TXT);
        UIManager.put("ComboBox.selectionBackground", ACC);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("CheckBox.background", BG);
        UIManager.put("CheckBox.foreground", TXT);
        UIManager.put("Spinner.background", PANEL);
        UIManager.put("Spinner.foreground", TXT);
        UIManager.put("ProgressBar.foreground", ACC);
        UIManager.put("ProgressBar.background", PANEL);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", TXT);
    }

    Main() {
        Path dir = dataDir();
        core = new Core(dir);
        settingsFile = dir.resolve("launcher.json");
    }

    // ------------------------------------------------------------------ fenetre

    private void show() {
        JFrame f = new JFrame(title);
        frame = f;
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 14, 0);

        titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 24f));
        titleLabel.setForeground(ACC);
        JPanel head = new JPanel(new BorderLayout());
        head.add(titleLabel, BorderLayout.CENTER);
        head.add(btn("Couleur", this::pickColor), BorderLayout.EAST);
        p.add(head, c);

        c.gridwidth = 1;
        c.insets = new Insets(4, 0, 4, 10);
        row(p, c, "Pseudo", user);
        row(p, c, "Version", versions);
        c.gridx = 1;
        c.gridy++;
        p.add(snapshots, c);
        row(p, c, "RAM (Mo)", ram);
        row(p, c, "Serveur (auto)", server);
        row(p, c, "Java (optionnel)", java);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.insets = new Insets(16, 0, 6, 0);
        play.setBackground(ACC);
        play.setForeground(Color.WHITE);
        play.setFont(play.getFont().deriveFont(Font.BOLD, 18f));
        play.setFocusPainted(false);
        play.setBorder(BorderFactory.createEmptyBorder(14, 0, 14, 0));
        p.add(play, c);

        c.gridy++;
        c.insets = new Insets(4, 0, 4, 0);
        bar.setBorderPainted(false);
        p.add(bar, c);

        c.gridy++;
        status.setForeground(MUT);
        p.add(status, c);

        c.gridy++;
        c.insets = new Insets(2, 0, 0, 0);
        closeOnLaunch.setForeground(MUT);
        fullscreen.setForeground(MUT);
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.add(fullscreen);
        checks.add(Box.createHorizontalStrut(16));
        checks.add(closeOnLaunch);
        p.add(checks, c);

        c.gridy++;
        c.insets = new Insets(10, 0, 0, 0);
        JPanel grid = new JPanel(new GridLayout(3, 3, 8, 8));
        grid.add(btn("Dossier du jeu", () -> openDir(core.root)));
        grid.add(btn("Dossier mods", () -> openDir(core.root.resolve("mods"))));
        grid.add(btn("Captures d'écran", () -> openDir(core.root.resolve("screenshots"))));
        grid.add(btn("Voir le log", this::showLog));
        grid.add(btn("Rafraîchir versions", this::loadVersions));
        stop.setEnabled(false);
        stop.addActionListener(e -> { Process pr = running; if (pr != null) pr.destroy(); });
        grid.add(stop);
        grid.add(btn("Supprimer la version", this::deleteVersion));
        grid.add(btn("Réglages", this::openSettings));
        grid.add(btn("Mise à jour", () -> checkUpdate(true)));
        p.add(grid, c);

        f.setContentPane(p);
        f.pack();
        f.setSize(Math.max(f.getWidth(), 560), f.getHeight());
        f.setResizable(false);
        f.setLocationRelativeTo(null);
        ram.setEditor(new JSpinner.NumberEditor(ram, "#"));
        loadSettings();
        play.addActionListener(e -> play());
        snapshots.addActionListener(e -> fillVersions());
        f.setVisible(true);
        loadVersions();
        checkUpdate(false);
    }

    private void row(JPanel p, GridBagConstraints c, String label, JComponent comp) {
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        JLabel l = new JLabel(label);
        l.setForeground(MUT);
        p.add(l, c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(comp, c);
    }

    private static JButton btn(String text) {
        JButton b = new JButton(text);
        b.setBackground(PANEL);
        b.setForeground(TXT);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        return b;
    }

    private static JButton btn(String text, Runnable action) {
        JButton b = btn(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void openDir(Path dir) {
        try {
            Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            status.setText("Impossible d'ouvrir " + dir);
        }
    }

    // ------------------------------------------------------------------ log du dernier lancement

    private void showLog() {
        Path log = core.root.resolve("logs/dernier-lancement.log");
        String text;
        try {
            List<String> lines = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
            text = lines.isEmpty() ? "(Le log est vide : lance d'abord le jeu.)"
                    : String.join("\n", lines.subList(Math.max(0, lines.size() - 120), lines.size()));
        } catch (IOException e) {
            text = "Impossible de lire le log : " + e.getMessage();
        }
        JTextArea area = new JTextArea(text, 18, 70);
        area.setEditable(false);
        area.setBackground(PANEL);
        area.setForeground(TXT);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(area.getDocument().getLength());
        final String copy = text;
        JButton copyBtn = btn("Copier le log", () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(copy), null);
            status.setText("Log copié : colle-le dans la conversation.");
        });
        JPanel pn = new JPanel(new BorderLayout(0, 8));
        pn.add(new JScrollPane(area), BorderLayout.CENTER);
        pn.add(copyBtn, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(frame, pn, "Log du dernier lancement", JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------ supprimer une version

    private void deleteVersion() {
        Core.Entry e = (Core.Entry) versions.getSelectedItem();
        if (e == null) {
            status.setText("Choisis une version.");
            return;
        }
        Path dir = core.root.resolve("versions").resolve(e.id());
        if (!Files.isDirectory(dir)) {
            status.setText("Cette version n'est pas installée.");
            return;
        }
        int r = JOptionPane.showConfirmDialog(frame,
                "Supprimer la version " + e.id() + " de ton PC ?\n(Tes mondes, mods et réglages ne sont pas touchés.)",
                "Supprimer", JOptionPane.YES_NO_OPTION);
        if (r != JOptionPane.YES_OPTION) return;
        int[] failed = {0};
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.delete(x);
                } catch (IOException ex) {
                    failed[0]++;
                }
            });
        } catch (IOException ex) {
            failed[0]++;
        }
        if (failed[0] > 0 || Files.exists(dir)) {
            loadVersions("Suppression incomplète : ferme Minecraft puis réessaie.");
        } else {
            loadVersions("Version " + e.id() + " supprimée (reste listée : dispo en ligne).");
        }
    }

    // ------------------------------------------------------------------ reglages

    private void openSettings() {
        JTextField nameField = new JTextField(title, 20);
        JTextField urlField = new JTextField(updateUrl, 20);
        Color[] chosen = {ACC};
        JButton colorBtn = btn("Choisir la couleur");
        colorBtn.setBackground(ACC);
        colorBtn.setForeground(Color.WHITE);
        colorBtn.addActionListener(e -> {
            Color col = JColorChooser.showDialog(frame, "Couleur principale", chosen[0]);
            if (col != null) {
                chosen[0] = col;
                colorBtn.setBackground(col);
            }
        });

        JPanel pn = new JPanel(new GridLayout(0, 2, 8, 8));
        pn.add(new JLabel("Nom du launcher"));
        pn.add(nameField);
        pn.add(new JLabel("Couleur principale"));
        pn.add(colorBtn);
        pn.add(new JLabel("Adresse de mise à jour"));
        pn.add(urlField);

        int r = JOptionPane.showConfirmDialog(frame, pn, "Réglages", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        title = nameField.getText().isBlank() ? DEFAULT_TITLE : nameField.getText().trim();
        titleLabel.setText(title);
        frame.setTitle(title);
        applyAccent(chosen[0]);
        updateUrl = urlField.getText().trim();
        saveSettings(selectedId());
        status.setText("Réglages enregistrés.");
    }

    /** Bouton rapide "Couleur" : change la couleur principale tout de suite, sans redemarrer. */
    private void pickColor() {
        Color col = JColorChooser.showDialog(frame, "Couleur principale", ACC);
        if (col == null) return;
        applyAccent(col);
        saveSettings(selectedId());
    }

    private void applyAccent(Color col) {
        ACC = col;
        UIManager.put("ComboBox.selectionBackground", ACC);
        UIManager.put("ProgressBar.foreground", ACC);
        titleLabel.setForeground(ACC);
        play.setBackground(ACC);
        bar.setForeground(ACC);
        frame.repaint();
    }

    // ------------------------------------------------------------------ mise a jour du launcher

    /** Lit version.txt (a cote du .jar en ligne) et le compare au numero de version de ce launcher ; si plus recent, telecharge le .jar et redemarre. */
    private void checkUpdate(boolean manual) {
        if (updateUrl.isBlank()) {
            if (manual) status.setText("Aucune adresse de mise à jour : renseigne-la dans Réglages.");
            return;
        }
        if (manual) status.setText("Recherche de mise à jour...");
        new Thread(() -> {
            try {
                HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10)).build();
                String versionUrl = updateUrl.substring(0, updateUrl.lastIndexOf('/') + 1) + "version.txt";
                HttpResponse<String> vr = http.send(HttpRequest.newBuilder(URI.create(versionUrl))
                        .header("Cache-Control", "no-cache").timeout(Duration.ofSeconds(15)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (vr.statusCode() != 200) throw new IOException("version.txt introuvable (HTTP " + vr.statusCode() + ")");
                int remote = Integer.parseInt(vr.body().trim());

                if (remote <= VERSION) {
                    if (manual) SwingUtilities.invokeLater(() -> status.setText("Le launcher est à jour (version " + VERSION + ")."));
                    return;
                }
                SwingUtilities.invokeLater(() -> status.setText("Téléchargement de la mise à jour (version " + remote + ")..."));
                Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (!jar.toString().endsWith(".jar")) throw new IOException("lancé hors d'un .jar");
                Path fresh = jar.resolveSibling(jar.getFileName() + ".new");
                HttpResponse<Path> resp = http.send(HttpRequest.newBuilder(URI.create(updateUrl))
                        .header("Cache-Control", "no-cache").timeout(Duration.ofMinutes(2)).build(),
                        HttpResponse.BodyHandlers.ofFile(fresh));
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(frame, "Une mise à jour est installée (version " + remote + "). Le launcher va redémarrer.");
                    restartWith(jar, fresh);
                });
            } catch (Exception e) {
                String msg = "Mise à jour impossible : " + e.getMessage();
                SwingUtilities.invokeLater(() -> status.setText(manual ? msg : "Launcher version " + VERSION + " (" + msg + ")"));
            }
        }, "update").start();
    }

    /** Remplace l'ancien jar par le nouveau une fois ce programme ferme, puis le relance. */
    private void restartWith(Path jar, Path fresh) {
        try {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", Core.OS.equals("windows") ? "javaw.exe" : "java").toString();
            ProcessBuilder pb;
            if (Core.OS.equals("windows")) {
                pb = new ProcessBuilder("cmd", "/c", "ping -n 3 127.0.0.1 >nul & move /y \"" + fresh + "\" \"" + jar
                        + "\" & start \"\" \"" + javaBin + "\" -jar \"" + jar + "\"");
            } else {
                pb = new ProcessBuilder("sh", "-c", "sleep 2; mv -f '" + fresh + "' '" + jar + "'; '" + javaBin + "' -jar '" + jar + "' &");
            }
            pb.start();
        } catch (Exception e) {
            status.setText("Redémarrage impossible : " + e.getMessage());
            return;
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ versions

    private String selectedId() {
        Object o = versions.getSelectedItem();
        return o instanceof Core.Entry e ? e.id() : wantedVersion;
    }

    private void loadVersions() {
        loadVersions("");
    }

    private void loadVersions(String doneMsg) {
        status.setText("Chargement des versions...");
        new Thread(() -> {
            List<Core.Entry> list = new ArrayList<>(core.localVersions());
            String msg = doneMsg;
            try {
                for (Core.Entry r : core.remoteVersions()) {
                    if (list.stream().noneMatch(x -> x.id().equals(r.id()))) list.add(r);
                    else list.replaceAll(x -> x.id().equals(r.id()) ? r : x);
                }
            } catch (Exception e) {
                msg = (doneMsg.isEmpty() ? "" : doneMsg + " ") + "Hors ligne : seules les versions deja installees sont listees.";
            }
            String finalMsg = msg;
            SwingUtilities.invokeLater(() -> {
                all.clear();
                all.addAll(list);
                fillVersions();
                status.setText(finalMsg);
            });
        }, "versions").start();
    }

    private void fillVersions() {
        String wanted = selectedId();
        versions.removeAllItems();
        Core.Entry selected = null;
        boolean first = true;
        for (Core.Entry e : all) {
            boolean visible = "local".equals(e.type()) || "release".equals(e.type()) || snapshots.isSelected();
            if (!visible) continue;
            versions.addItem(e);
            if (e.id().equals(wanted)) {
                selected = e;
            } else if (selected == null && first && "release".equals(e.type())) {
                first = false;
                if (wanted.isEmpty()) selected = e;
            }
        }
        if (selected != null) versions.setSelectedItem(selected);
    }

    // ------------------------------------------------------------------ jouer

    private void play() {
        Core.Entry entry = (Core.Entry) versions.getSelectedItem();
        if (entry == null) {
            status.setText("Choisis une version.");
            return;
        }
        String name = user.getText().trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            status.setText("Pseudo invalide : 3 a 16 caracteres (lettres, chiffres, _).");
            return;
        }
        Core.Options o = new Core.Options();
        o.user = name;
        o.memoryMb = (Integer) ram.getValue();
        o.java = java.getText();
        o.server = server.getText();
        o.fullscreen = fullscreen.isSelected();
        saveSettings(entry.id());
        play.setEnabled(false);
        new Thread(() -> {
            try {
                Process proc = core.launch(entry, all, o, (text, frac) -> SwingUtilities.invokeLater(() -> {
                    status.setText(text);
                    bar.setValue((int) (frac * 1000));
                }));
                running = proc;
                SwingUtilities.invokeLater(() -> {
                    status.setText("Minecraft est lance. Bon jeu !");
                    bar.setValue(1000);
                    stop.setEnabled(true);
                    if (closeOnLaunch.isSelected()) {
                        Timer t = new Timer(3000, ev -> System.exit(0));
                        t.setRepeats(false);
                        t.start();
                    }
                });
                int code = proc.waitFor();
                running = null;
                SwingUtilities.invokeLater(() -> {
                    stop.setEnabled(false);
                    if (code != 0) status.setText("Le jeu s'est ferme (code " + code + "). Voir logs/dernier-lancement.log");
                    else status.setText("Jeu ferme.");
                    bar.setValue(0);
                    play.setEnabled(true);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    stop.setEnabled(false);
                    status.setText("Erreur : " + e.getMessage());
                    bar.setValue(0);
                    play.setEnabled(true);
                });
            }
        }, "launch").start();
    }

    // ------------------------------------------------------------------ sauvegarde des reglages

    private void loadSettings() {
        user.setText("Joueur");
        Map<String, Object> m = readSettings(settingsFile);
        if (m.get("user") instanceof String s) user.setText(s);
        if (m.get("ram") instanceof Number n) ram.setValue(n.intValue());
        if (m.get("java") instanceof String s) java.setText(s);
        if (m.get("server") instanceof String s) server.setText(s);
        if (m.get("snapshots") instanceof Boolean b) snapshots.setSelected(b);
        if (m.get("version") instanceof String s) wantedVersion = s;
        if (m.get("close") instanceof Boolean b) closeOnLaunch.setSelected(b);
        if (m.get("fullscreen") instanceof Boolean b) fullscreen.setSelected(b);
        if (sameDefaults(m) && m.get("updateUrl") instanceof String s && !s.isBlank()) updateUrl = s;
        if (m.get("updateTag") instanceof String s) updateTag = s;
    }

    private void saveSettings(String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", user.getText().trim());
        m.put("ram", ram.getValue());
        m.put("java", java.getText().trim());
        m.put("server", server.getText().trim());
        m.put("snapshots", snapshots.isSelected());
        m.put("version", version);
        m.put("close", closeOnLaunch.isSelected());
        m.put("fullscreen", fullscreen.isSelected());
        m.put("title", title);
        m.put("defaultsId", defaultsId());
        m.put("accent", ACC.getRGB() & 0xFFFFFF);
        m.put("updateUrl", updateUrl);
        m.put("updateTag", updateTag);
        try {
            Files.createDirectories(core.root);
            Files.writeString(settingsFile, Json.write(m));
        } catch (IOException e) {
            // pas grave
        }
    }
}
