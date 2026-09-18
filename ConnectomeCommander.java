/*
 * ============================================================================
 *  CONNECTOME COMMANDER  -  NASA Space Apps Challenge (48h hackathon build)
 * ============================================================================
 *  A retro space shooter piloted by a (stylised) fruit-fly brain.
 *
 *    LEFT  : 2D arcade canvas - dodge asteroids, grab green repair pods.
 *    RIGHT : Fly-brain "navigation computer" - regions glow teal when healthy
 *            and turn red / glitch when hit by radiation from REAL solar flares
 *            fetched from the NASA DONKI (Space Weather) API at start-up.
 *
 *  THE CORE MECHANIC
 *    NASA flare data --> RadiationModel --> per-region damage (0..100 %)
 *    Central Complex damage >= CRITICAL  --> BrainState.steeringReversed = true
 *    GameWorld multiplies the steering axis by -1  --> LEFT moves right, etc.
 *
 *  RUN (no build tool, no dependencies, JDK 11+):
 *      java ConnectomeCommander.java
 *      java ConnectomeCommander.java --demo            (skip the network)
 *      java ConnectomeCommander.java --key=YOUR_KEY    (or env NASA_API_KEY)
 *      java ConnectomeCommander.java --days=14         (flare look-back window)
 *
 *  CONTROLS
 *      Arrows / WASD  steer      ENTER  launch / relaunch      P  pause
 *      F1  inject a simulated solar storm (great for live demos!)
 *      F2  clear all radiation   F5  re-fetch NASA data (when not mid-run)
 *
 *  DESIGN NOTES
 *    * Everything game-related runs on the Swing EDT (one javax.swing.Timer),
 *      so there are no locks and no race conditions. The HTTP call is async
 *      and hops back onto the EDT with SwingUtilities.invokeLater.
 *    * If the network/API fails (hackathon Wi-Fi!), we fall back to a clearly
 *      labelled SIMULATED storm so the demo never dies.
 *    * The brain map is a STYLISED schematic, not the real synapse-level data.
 *      See the notes at the bottom of this file for wiring in real neuPrint
 *      hemibrain ROI names.
 * ============================================================================
 */

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConnectomeCommander {

    // =========================================================================
    //  CONFIG - tweak these to tune difficulty / demo behaviour
    // =========================================================================
    static final int GAME_W = 660;              // left canvas width  (px)
    static final int BRAIN_W = 440;             // right panel width (px)
    static final int PANEL_H = 740;             // shared height     (px)
    static final int FRAME_MS = 16;             // ~60 FPS

    /** Damage (%) at which a region is "CRITICAL" (red + glitching + effect active). */
    static final double CRITICAL_DAMAGE = 25.0;
    /** The strongest flare hits the Central Complex at full strength; every other
     *  flare is spread over the remaining regions at this reduced weight. */
    static final double SECONDARY_COUPLING = 0.15;
    static final int DEFAULT_LOOKBACK_DAYS = 7;

    static final double SHIP_SPEED = 340.0;     // px/s at 100 % thrusters
    static final double SHIP_R = 13.0;

    static final Font F_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    static final Font F_MONO_B = new Font(Font.MONOSPACED, Font.BOLD, 12);
    static final Font F_HUD = new Font(Font.MONOSPACED, Font.BOLD, 15);
    static final Font F_BIG = new Font(Font.MONOSPACED, Font.BOLD, 30);
    static final Font F_TITLE = new Font(Font.MONOSPACED, Font.BOLD, 16);

    // =========================================================================
    //  ENTRY POINT
    // =========================================================================
    public static void main(String[] args) {
        boolean demo = false;
        int days = DEFAULT_LOOKBACK_DAYS;
        String key = System.getenv("NASA_API_KEY");
        for (String a : args) {
            if (a.equals("--demo")) demo = true;
            else if (a.startsWith("--key=")) key = a.substring(6);
            else if (a.startsWith("--days=")) days = Math.max(1, Math.min(30, Integer.parseInt(a.substring(7))));
        }
        if (key == null || key.isBlank()) key = "DEMO_KEY";   // NASA's public, rate-limited key

        final boolean fDemo = demo;
        final int fDays = days;
        final String fKey = key;
        SwingUtilities.invokeLater(() -> new App(fKey, fDemo, fDays).showWindow());
    }

    // =========================================================================
    //  BRAIN REGIONS + stylised layout (unit-square coordinates)
    // =========================================================================
    /** Each region has a gameplay effect when its damage reaches CRITICAL_DAMAGE. */
    enum BrainRegion {
        OPTIC_LOBE("Optic Lobes", "vision  : HUD static"),
        MUSHROOM_BODY("Mushroom Body", "memory  : score gain"),
        CENTRAL_COMPLEX("Central Complex", "steering: CONTROLS INVERT"),
        ANTENNAL_LOBE("Antennal Lobes", "sensors : proximity radar"),
        SUBESOPHAGEAL("Subesophageal Zone", "motor   : thruster power");

        final String label;
        final String effect;

        BrainRegion(String label, String effect) {
            this.label = label;
            this.effect = effect;
        }
    }

    /** Each region is one or more ellipses in a 0..1 square (mirrored pairs where anatomical). */
    static final EnumMap<BrainRegion, List<Ellipse2D.Double>> LAYOUT = new EnumMap<>(BrainRegion.class);

    static {
        LAYOUT.put(BrainRegion.OPTIC_LOBE, List.of(
                new Ellipse2D.Double(0.00, 0.28, 0.26, 0.44), new Ellipse2D.Double(0.74, 0.28, 0.26, 0.44)));
        LAYOUT.put(BrainRegion.MUSHROOM_BODY, List.of(
                new Ellipse2D.Double(0.22, 0.03, 0.20, 0.28), new Ellipse2D.Double(0.58, 0.03, 0.20, 0.28)));
        LAYOUT.put(BrainRegion.CENTRAL_COMPLEX, List.of(
                new Ellipse2D.Double(0.36, 0.36, 0.28, 0.22)));
        LAYOUT.put(BrainRegion.ANTENNAL_LOBE, List.of(
                new Ellipse2D.Double(0.28, 0.64, 0.15, 0.14), new Ellipse2D.Double(0.57, 0.64, 0.15, 0.14)));
        LAYOUT.put(BrainRegion.SUBESOPHAGEAL, List.of(
                new Ellipse2D.Double(0.30, 0.82, 0.40, 0.15)));
    }

    // =========================================================================
    //  NASA DONKI: data model + HTTP client + tiny JSON parser
    // =========================================================================

    /** One solar flare from DONKI /FLR (only the fields we care about). */
    static final class FlareEvent {
        final String id, classType, peakTime, sourceLocation;
        final double severity;   // 0..1 (log-scaled X-ray flux; C1 ~0.29, M1 ~0.57, X1 ~0.86)

        private FlareEvent(String id, String classType, String peakTime, String loc, double severity) {
            this.id = id;
            this.classType = classType;
            this.peakTime = peakTime;
            this.sourceLocation = loc;
            this.severity = severity;
        }

        private static final Pattern CLASS_RE = Pattern.compile("^([ABCMX])(\\d*(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);

        /** Parses a GOES class like "M5.1" into a severity. Returns null if unparsable. */
        static FlareEvent from(String id, String classType, String peakTime, String loc) {
            if (classType == null) return null;
            Matcher m = CLASS_RE.matcher(classType.trim());
            if (!m.matches()) return null;
            char letter = Character.toUpperCase(m.group(1).charAt(0));
            double mag = m.group(2).isEmpty() ? 1.0 : Double.parseDouble(m.group(2));
            // GOES peak flux (W/m^2): A=1e-8, B=1e-7, C=1e-6, M=1e-5, X=1e-4
            int exp;
            switch (letter) {
                case 'A': exp = -8; break;
                case 'B': exp = -7; break;
                case 'C': exp = -6; break;
                case 'M': exp = -5; break;
                default:  exp = -4; break;
            }
            double flux = Math.max(mag, 0.1) * Math.pow(10, exp);
            double sev = clamp((Math.log10(flux) + 7.0) / 3.5, 0, 1);
            return new FlareEvent(id == null ? String.valueOf(peakTime) : id,
                    classType.trim().toUpperCase(Locale.ROOT),
                    peakTime == null ? "?" : peakTime, loc == null ? "" : loc, sev);
        }
    }

    /** Where the flare data came from - shown in the UI so judges know what's live. */
    enum Source { LOADING, LIVE, SIMULATED, OFFLINE_FALLBACK }

    static final class SolarReport {
        final Source source;
        final List<FlareEvent> flares;
        final String note;
        final int days;

        SolarReport(Source source, List<FlareEvent> flares, String note, int days) {
            this.source = source;
            this.flares = flares;
            this.note = note;
            this.days = days;
        }

        static SolarReport loading() {
            return new SolarReport(Source.LOADING, List.of(), "contacting api.nasa.gov ...", 0);
        }

        FlareEvent strongest() {
            FlareEvent best = null;
            for (FlareEvent f : flares) if (best == null || f.severity > best.severity) best = f;
            return best;
        }
    }

    /** Async HTTP client for DONKI. Uses only java.net.http (JDK 11+) - no libraries. */
    static final class NasaDonkiClient {
        private static final String ENDPOINT = "https://api.nasa.gov/DONKI/FLR";
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        private final String apiKey;

        NasaDonkiClient(String apiKey) {
            this.apiKey = apiKey;
        }

        /** Non-blocking: the UI never freezes while we wait on NASA. */
        CompletableFuture<List<FlareEvent>> fetchRecentFlares(int days) {
            LocalDate end = LocalDate.now(ZoneOffset.UTC);
            LocalDate start = end.minusDays(days);
            URI uri = URI.create(ENDPOINT + "?startDate=" + start + "&endDate=" + end + "&api_key=" + apiKey);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET().build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
                if (resp.statusCode() != 200)
                    throw new CompletionException(new IOException("NASA API returned HTTP " + resp.statusCode()
                            + (resp.statusCode() == 429 ? " (rate limited - use your own free key)" : "")));
                return parseFlares(resp.body());
            });
        }

        // ---- Minimal JSON handling: DONKI returns a flat array of objects; we only need 4 string fields ----

        static List<FlareEvent> parseFlares(String json) {
            List<FlareEvent> out = new ArrayList<>();
            if (json == null || json.isBlank()) return out;          // DONKI sends an empty body for "no events"
            for (String obj : splitTopLevelObjects(json)) {
                FlareEvent f = FlareEvent.from(jsonString(obj, "flrID"), jsonString(obj, "classType"),
                        jsonString(obj, "peakTime"), jsonString(obj, "sourceLocation"));
                if (f != null) out.add(f);
            }
            return out;
        }

        /** Splits "[{...},{...}]" into its top-level {...} chunks; brace-depth aware and string/escape safe. */
        static List<String> splitTopLevelObjects(String json) {
            List<String> objs = new ArrayList<>();
            int depth = 0, start = -1;
            boolean inStr = false, esc = false;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inStr) {
                    if (esc) esc = false;
                    else if (c == '\\') esc = true;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (c == '"') inStr = true;
                else if (c == '{') { if (depth++ == 0) start = i; }
                else if (c == '}') { if (--depth == 0 && start >= 0) objs.add(json.substring(start, i + 1)); }
            }
            return objs;
        }

        static String jsonString(String obj, String key) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
            return m.find() ? m.group(1) : null;
        }
    }

    /** Built-in storm used for --demo, F1, and offline fallback. */
    static List<FlareEvent> demoFlares() {
        List<FlareEvent> l = new ArrayList<>();
        String[][] d = {
                {"DEMO-1", "X2.8", "S17W74"}, {"DEMO-2", "M5.1", "N12E20"}, {"DEMO-3", "M1.2", "S08W33"},
                {"DEMO-4", "C7.3", "N20W10"}, {"DEMO-5", "C2.2", "S15E45"}, {"DEMO-6", "M3.4", "N09W61"},
                {"DEMO-7", "C1.4", "S22W05"}, {"DEMO-8", "C5.0", "N14E02"}};
        for (String[] r : d) {
            FlareEvent f = FlareEvent.from(r[0], r[1], "SIMULATED", r[2]);
            if (f != null) l.add(f);
        }
        return l;
    }

    // =========================================================================
    //  RADIATION MODEL: flares -> per-region damage
    // =========================================================================
    static final class RadiationModel {
        private static final BrainRegion[] SECONDARY = {
                BrainRegion.OPTIC_LOBE, BrainRegion.MUSHROOM_BODY,
                BrainRegion.ANTENNAL_LOBE, BrainRegion.SUBESOPHAGEAL};

        /**
         * Game-design rules (deliberately simple + explainable to judges):
         *  1. The STRONGEST flare in the window hits the Central Complex at full strength.
         *  2. Every other flare hits one of the other regions (picked by its ID hash) at 15 % strength.
         *  3. Hits to the same region combine like independent probabilities: 1 - prod(1 - hit),
         *     so damage is always bounded to 0..100 %.
         *  The Central Complex trips at CRITICAL_DAMAGE (25 %), i.e. a peak flare of roughly B8 or stronger;
         *  a genuinely quiet sun therefore means NO reversed controls (use F1 to demo anyway).
         */
        static EnumMap<BrainRegion, Double> compute(List<FlareEvent> flares) {
            EnumMap<BrainRegion, Double> survive = new EnumMap<>(BrainRegion.class);
            for (BrainRegion r : BrainRegion.values()) survive.put(r, 1.0);

            FlareEvent strongest = null;
            for (FlareEvent f : flares) if (strongest == null || f.severity > strongest.severity) strongest = f;

            for (FlareEvent f : flares) {
                BrainRegion target;
                double weight;
                if (f == strongest) {
                    target = BrainRegion.CENTRAL_COMPLEX;
                    weight = 1.0;
                } else {
                    target = SECONDARY[Math.floorMod(f.id.hashCode(), SECONDARY.length)];
                    weight = SECONDARY_COUPLING;
                }
                survive.merge(target, 1.0 - f.severity * weight, (a, b) -> a * b);
            }
            EnumMap<BrainRegion, Double> out = new EnumMap<>(BrainRegion.class);
            for (BrainRegion r : BrainRegion.values()) out.put(r, 100.0 * (1.0 - survive.get(r)));
            return out;
        }
    }

    // =========================================================================
    //  BRAIN STATE: the single source of truth that couples NASA data to physics
    // =========================================================================
    static final class BrainState {
        private final EnumMap<BrainRegion, Double> damage = new EnumMap<>(BrainRegion.class);

        /** THE FLAG. Flipped the instant Central Complex damage crosses the critical threshold. */
        volatile boolean steeringReversed;

        BrainState() {
            for (BrainRegion r : BrainRegion.values()) damage.put(r, 0.0);
        }

        double damage(BrainRegion r) {
            return damage.get(r);
        }

        boolean isCritical(BrainRegion r) {
            return damage.get(r) >= CRITICAL_DAMAGE;
        }

        void setAll(Map<BrainRegion, Double> src) {
            for (BrainRegion r : BrainRegion.values()) damage.put(r, clamp(src.getOrDefault(r, 0.0), 0, 100));
            refresh();
        }

        /** Repairs the most-damaged region; returns its label (or null if the brain is healthy). */
        String healWorst(double amount) {
            BrainRegion worst = null;
            for (BrainRegion r : BrainRegion.values())
                if (damage.get(r) > 0.5 && (worst == null || damage.get(r) > damage.get(worst))) worst = r;
            if (worst == null) return null;
            damage.put(worst, Math.max(0, damage.get(worst) - amount));
            refresh();
            return worst.label;
        }

        private void refresh() {
            steeringReversed = isCritical(BrainRegion.CENTRAL_COMPLEX);
        }

        /** +1 normally, -1 when the Central Complex is fried. Multiply the steering axis by this. */
        double steeringMultiplier() {
            return steeringReversed ? -1.0 : 1.0;
        }

        /** 1.0 healthy ... 0.4 when the Subesophageal Zone (motor) is fully damaged. */
        double thrustFactor() {
            return 1.0 - 0.6 * damage.get(BrainRegion.SUBESOPHAGEAL) / 100.0;
        }
    }

    // =========================================================================
    //  INPUT: global key tracking (immune to focus problems)
    // =========================================================================
    static final class Input {
        volatile boolean left, right, up, down;
        private final Set<Integer> held = new HashSet<>();

        void install(IntConsumer onPress) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                boolean pressed = e.getID() == KeyEvent.KEY_PRESSED;
                if (!pressed && e.getID() != KeyEvent.KEY_RELEASED) return false;
                int c = e.getKeyCode();
                switch (c) {
                    case KeyEvent.VK_LEFT: case KeyEvent.VK_A: left = pressed; break;
                    case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: right = pressed; break;
                    case KeyEvent.VK_UP: case KeyEvent.VK_W: up = pressed; break;
                    case KeyEvent.VK_DOWN: case KeyEvent.VK_S: down = pressed; break;
                    default: break;
                }
                if (pressed) { if (held.add(c)) onPress.accept(c); }   // edge-triggered (ignores auto-repeat)
                else held.remove(c);
                return false;
            });
        }

        void reset() {
            left = right = up = down = false;
            held.clear();
        }
    }

    // =========================================================================
    //  GAME WORLD: entities + rules (no Swing drawing in here)
    // =========================================================================
    static final class Asteroid {
        double x, y, vx, vy, r, rot, spin;
        final double[] radii = new double[10];      // jagged outline

        Asteroid(Random rnd, double x, double r, double vx, double vy) {
            this.x = x; this.y = -r; this.r = r; this.vx = vx; this.vy = vy;
            this.rot = rnd.nextDouble() * 6.28;
            this.spin = (rnd.nextDouble() - 0.5) * 2.5;
            for (int i = 0; i < radii.length; i++) radii[i] = r * (0.72 + 0.34 * rnd.nextDouble());
        }
    }

    static final class Pod {
        double x, y, vy = 75, age;
        static final double R = 12;

        Pod(double x) {
            this.x = x;
            this.y = -R;
        }
    }

    static final class GameWorld {
        enum Phase { LOADING, BRIEFING, PLAYING, GAME_OVER }

        Phase phase = Phase.LOADING;
        boolean paused;
        final BrainState brain;
        final Random rnd = new Random();

        double shipX, shipY, shipVX, shipVY;
        int hull;
        double invuln, hitFlash, elapsed, score;
        long frame;
        final List<Asteroid> asteroids = new ArrayList<>();
        final List<Pod> pods = new ArrayList<>();
        double spawnTimer, podTimer;
        String toast = "";
        double toastTimer;
        Asteroid nearest;
        double nearestDist = 1e9;

        final double[] sx = new double[90], sy = new double[90], sv = new double[90];

        GameWorld(BrainState brain) {
            this.brain = brain;
            for (int i = 0; i < sx.length; i++) {
                sx[i] = rnd.nextDouble() * GAME_W;
                sy[i] = rnd.nextDouble() * PANEL_H;
                sv[i] = 20 + rnd.nextDouble() * 110;
            }
            reset();
        }

        void reset() {
            shipX = GAME_W / 2.0;
            shipY = PANEL_H - 110;
            shipVX = shipVY = 0;
            hull = 3;
            invuln = hitFlash = elapsed = score = 0;
            asteroids.clear();
            pods.clear();
            spawnTimer = 0.5;
            podTimer = 5;
            nearest = null;
            nearestDist = 1e9;
        }

        void start() {
            reset();
            phase = Phase.PLAYING;
        }

        void toast(String msg, double secs) {
            toast = msg;
            toastTimer = secs;
        }

        /** Called once per frame by the master Timer. dt is in seconds (clamped by the caller). */
        void update(double dt, Input in) {
            frame++;
            double starBoost = phase == Phase.PLAYING ? 1.0 : 0.3;
            for (int i = 0; i < sx.length; i++) {
                sy[i] += sv[i] * dt * starBoost;
                if (sy[i] > PANEL_H) { sy[i] = 0; sx[i] = rnd.nextDouble() * GAME_W; }
            }
            if (toastTimer > 0) toastTimer -= dt;
            if (hitFlash > 0) hitFlash -= dt;
            if (phase != Phase.PLAYING) return;

            elapsed += dt;
            invuln = Math.max(0, invuln - dt);
            // Memory (Mushroom Body) damage taxes your score rate by up to 50 %.
            score += dt * 10.0 * (1.0 - 0.5 * brain.damage(BrainRegion.MUSHROOM_BODY) / 100.0);

            // ---------------- PLAYER PHYSICS (where NASA data meets the ship) ----------------
            double axisX = (in.left ? -1 : 0) + (in.right ? 1 : 0);
            double axisY = (in.up ? -1 : 0) + (in.down ? 1 : 0);
            axisX *= brain.steeringMultiplier();                 // <<< the -1 multiplier: Central Complex fried
            double speed = SHIP_SPEED * brain.thrustFactor();    // motor damage throttles the thrusters
            double k = Math.min(1.0, 14.0 * dt);                 // light smoothing so it feels like a ship
            shipVX += (axisX * speed - shipVX) * k;
            shipVY += (axisY * speed - shipVY) * k;
            shipX = clamp(shipX + shipVX * dt, SHIP_R, GAME_W - SHIP_R);
            shipY = clamp(shipY + shipVY * dt, 60 + SHIP_R, PANEL_H - SHIP_R);

            // ---------------- SPAWNING (difficulty ramps with time) ----------------
            spawnTimer -= dt;
            if (spawnTimer <= 0 && asteroids.size() < 45) {
                spawnTimer = Math.max(0.22, 0.75 - elapsed * 0.004);
                double r = 12 + rnd.nextDouble() * 26;
                asteroids.add(new Asteroid(rnd, rnd.nextDouble() * GAME_W, r,
                        (rnd.nextDouble() - 0.5) * 80, 95 + rnd.nextDouble() * 130 + elapsed * 1.8));
            }
            podTimer -= dt;
            if (podTimer <= 0) {
                podTimer = 6 + rnd.nextDouble() * 4;
                pods.add(new Pod(40 + rnd.nextDouble() * (GAME_W - 80)));
            }

            // ---------------- ASTEROIDS: move, cull, collide ----------------
            nearest = null;
            nearestDist = 1e9;
            for (Iterator<Asteroid> it = asteroids.iterator(); it.hasNext(); ) {
                Asteroid a = it.next();
                a.x += a.vx * dt;
                a.y += a.vy * dt;
                a.rot += a.spin * dt;
                if (a.y - a.r > PANEL_H || a.x < -90 || a.x > GAME_W + 90) { it.remove(); continue; }
                double dx = a.x - shipX, dy = a.y - shipY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < nearestDist) { nearestDist = dist; nearest = a; }
                double hit = a.r * 0.85 + SHIP_R * 0.8;          // slightly forgiving circle-vs-circle bounds
                if (invuln <= 0 && dist < hit) {
                    hull--;
                    invuln = 1.5;
                    hitFlash = 0.35;
                    it.remove();
                    if (hull <= 0) {
                        phase = Phase.GAME_OVER;
                        toast("HULL BREACH", 3);
                    }
                }
            }

            // ---------------- REPAIR PODS: heal the most damaged brain region ----------------
            for (Iterator<Pod> it = pods.iterator(); it.hasNext(); ) {
                Pod p = it.next();
                p.y += p.vy * dt;
                p.age += dt;
                if (p.y > PANEL_H + 20) { it.remove(); continue; }
                double dx = p.x - shipX, dy = p.y - shipY, rr = Pod.R + SHIP_R;
                if (dx * dx + dy * dy < rr * rr) {
                    it.remove();
                    String healed = brain.healWorst(30);
                    if (healed == null) { score += 50; toast("NEURAL PATCH: +50 (brain healthy)", 1.6); }
                    else toast("NEURAL PATCH: " + healed + " -30%", 2.0);
                }
            }
        }
    }

    // =========================================================================
    //  LEFT PANEL: the arcade canvas
    // =========================================================================
    static final class GamePanel extends JPanel {
        private final App app;

        GamePanel(App app) {
            this.app = app;
            setPreferredSize(new Dimension(GAME_W, PANEL_H));
            setFocusable(false);
            setDoubleBuffered(true);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            GameWorld w = app.world;
            BrainState brain = app.brain;

            g.setPaint(new GradientPaint(0, 0, new Color(4, 6, 18), 0, PANEL_H, new Color(16, 10, 36)));
            g.fillRect(0, 0, GAME_W, PANEL_H);
            for (int i = 0; i < w.sx.length; i++) {                      // parallax star field
                int b = 90 + (int) (w.sv[i] * 1.2);
                g.setColor(new Color(Math.min(255, b), Math.min(255, b), 255, 200));
                int s = w.sv[i] > 100 ? 2 : 1;
                g.fillRect((int) w.sx[i], (int) w.sy[i], s, s);
            }

            // World layer (shakes on impact)
            Graphics2D wg = (Graphics2D) g.create();
            if (w.hitFlash > 0) wg.translate((Math.random() - 0.5) * 10, (Math.random() - 0.5) * 10);
            drawPods(wg, w);
            drawAsteroids(wg, w);
            if (w.phase != GameWorld.Phase.LOADING && w.phase != GameWorld.Phase.BRIEFING) {
                drawRadar(wg, w, brain);
                drawShip(wg, w, brain);
            }
            wg.dispose();

            drawOpticNoise(g, w, brain);
            if (w.hitFlash > 0) {
                g.setColor(new Color(255, 40, 40, (int) (110 * Math.min(1.0, w.hitFlash / 0.35))));
                g.fillRect(0, 0, GAME_W, PANEL_H);
            }
            drawHud(g, w, brain);
            drawOverlay(g, w, brain);
            g.dispose();
            Toolkit.getDefaultToolkit().sync();    // smoother repaint on Linux
        }

        private void drawAsteroids(Graphics2D g, GameWorld w) {
            for (Asteroid a : w.asteroids) {
                Polygon p = new Polygon();
                for (int i = 0; i < a.radii.length; i++) {
                    double ang = a.rot + i * (Math.PI * 2 / a.radii.length);
                    p.addPoint((int) (a.x + Math.cos(ang) * a.radii[i]), (int) (a.y + Math.sin(ang) * a.radii[i]));
                }
                g.setColor(new Color(58, 56, 72));
                g.fillPolygon(p);
                g.setColor(new Color(170, 168, 196));
                g.setStroke(new BasicStroke(1.6f));
                g.drawPolygon(p);
            }
        }

        private void drawPods(Graphics2D g, GameWorld w) {
            for (Pod p : w.pods) {
                double pulse = 0.5 + 0.5 * Math.sin(p.age * 6);
                g.setColor(new Color(60, 255, 140, (int) (50 + 60 * pulse)));
                g.fill(new Ellipse2D.Double(p.x - Pod.R - 5, p.y - Pod.R - 5, 2 * Pod.R + 10, 2 * Pod.R + 10));
                g.setColor(new Color(40, 220, 120));
                g.fill(new Ellipse2D.Double(p.x - Pod.R, p.y - Pod.R, 2 * Pod.R, 2 * Pod.R));
                g.setColor(new Color(4, 30, 16));
                g.fillRect((int) p.x - 6, (int) p.y - 2, 12, 4);
                g.fillRect((int) p.x - 2, (int) p.y - 6, 4, 12);
            }
        }

        private void drawRadar(Graphics2D g, GameWorld w, BrainState brain) {
            if (w.phase != GameWorld.Phase.PLAYING || brain.isCritical(BrainRegion.ANTENNAL_LOBE)) return;
            boolean danger = w.nearestDist < 140;
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 6f}, 0f));
            g.setColor(danger ? new Color(255, 80, 80, 150) : new Color(0, 220, 200, 55));
            g.draw(new Ellipse2D.Double(w.shipX - 110, w.shipY - 110, 220, 220));
            if (w.nearest != null && w.nearestDist < 260) {          // blip on the ring pointing at the nearest rock
                double ang = Math.atan2(w.nearest.y - w.shipY, w.nearest.x - w.shipX);
                g.setColor(danger ? new Color(255, 90, 90) : new Color(0, 220, 200));
                g.fill(new Ellipse2D.Double(w.shipX + Math.cos(ang) * 110 - 4, w.shipY + Math.sin(ang) * 110 - 4, 8, 8));
            }
        }

        private void drawShip(Graphics2D g, GameWorld w, BrainState brain) {
            if (w.phase == GameWorld.Phase.GAME_OVER) return;
            if (w.invuln > 0 && ((int) (w.invuln * 12)) % 2 == 0) return;     // i-frame blink
            int x = (int) w.shipX, y = (int) w.shipY;
            boolean inverted = brain.steeringReversed;
            if (inverted && (w.frame / 4) % 2 == 0) {                         // glitch ghost while controls are inverted
                g.translate(4, 0);
                g.setColor(new Color(255, 40, 70, 110));
                g.fill(shipShape(x, y));
                g.translate(-4, 0);
            }
            double flame = 10 + 8 * Math.random();
            Polygon f = new Polygon(new int[]{x - 5, x + 5, x}, new int[]{y + 10, y + 10, (int) (y + 10 + flame)}, 3);
            g.setColor(new Color(255, 170, 40));
            g.fillPolygon(f);
            g.setColor(inverted ? new Color(255, 90, 100) : new Color(0, 230, 210));
            g.fill(shipShape(x, y));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(shipShape(x, y));
        }

        private Shape shipShape(int x, int y) {
            return new Polygon(new int[]{x, x - 12, x, x + 12}, new int[]{y - 16, y + 12, y + 6, y + 12}, 4);
        }

        /** Optic Lobe damage: TV static + darkening vignette over the play-field. */
        private void drawOpticNoise(Graphics2D g, GameWorld w, BrainState brain) {
            double d = brain.damage(BrainRegion.OPTIC_LOBE) / 100.0;
            if (d < 0.02) return;
            Random nr = new Random(w.frame * 31L);
            int n = (int) (d * 1100);
            for (int i = 0; i < n; i++) {
                g.setColor(new Color(255, 255, 255, 20 + nr.nextInt(60)));
                g.fillRect(nr.nextInt(GAME_W), nr.nextInt(PANEL_H), 2 + nr.nextInt(3), 1 + nr.nextInt(2));
            }
            if (nr.nextDouble() < d * 0.6) {                                   // occasional horizontal tear
                g.setColor(new Color(255, 255, 255, 40));
                g.fillRect(0, nr.nextInt(PANEL_H), GAME_W, 2 + nr.nextInt(8));
            }
            g.setPaint(new RadialGradientPaint(GAME_W / 2f, PANEL_H / 2f, PANEL_H * 0.75f, new float[]{0.35f, 1f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, (int) (200 * Math.min(1, d * 1.6)))}));
            g.fillRect(0, 0, GAME_W, PANEL_H);
        }

        private void drawHud(Graphics2D g, GameWorld w, BrainState brain) {
            g.setFont(F_HUD);
            g.setColor(new Color(0, 230, 210));
            g.drawString(String.format("SCORE %06d", (int) w.score), 16, 26);
            g.setColor(new Color(150, 165, 200));
            g.drawString(String.format("T+%03ds", (int) w.elapsed), 16, 46);
            for (int i = 0; i < 3; i++) {                                     // hull pips
                g.setColor(i < w.hull ? new Color(0, 230, 210) : new Color(50, 60, 80));
                g.fill(shipShape(GAME_W - 30 - i * 30, 30));
            }
            g.setFont(F_MONO);
            g.setColor(new Color(90, 105, 140));
            String sys = String.format("THRUST %d%%  |  RADAR %s  |  A/D or arrows  P pause  F1 storm  F2 clear",
                    (int) (brain.thrustFactor() * 100), brain.isCritical(BrainRegion.ANTENNAL_LOBE) ? "OFFLINE" : "ok");
            g.drawString(sys, 16, PANEL_H - 12);

            if (brain.steeringReversed && w.phase == GameWorld.Phase.PLAYING) {
                boolean blink = (w.frame / 20) % 2 == 0;
                g.setColor(new Color(120, 0, 20, blink ? 200 : 110));
                g.fillRoundRect(60, 60, GAME_W - 120, 30, 10, 10);
                g.setFont(F_MONO_B);
                g.setColor(new Color(255, 110, 120));
                drawCentered(g, "!! CENTRAL COMPLEX HIT - STEERING INVERTED (x -1) !!", GAME_W / 2, 80);
            }
            if (w.toastTimer > 0 && !w.toast.isEmpty()) {
                g.setFont(F_MONO_B);
                g.setColor(new Color(255, 255, 255, (int) (255 * Math.min(1, w.toastTimer))));
                drawCentered(g, w.toast, GAME_W / 2, 118);
            }
        }

        private void drawOverlay(Graphics2D g, GameWorld w, BrainState brain) {
            List<String> lines = new ArrayList<>();
            String title;
            Color titleColor = new Color(0, 230, 210);
            if (app.world.paused) {
                title = "PAUSED";
                lines.add("press P to resume");
            } else if (w.phase == GameWorld.Phase.LOADING) {
                title = "ACQUIRING SOLAR TELEMETRY";
                lines.add("NASA DONKI  ::  api.nasa.gov" + ".".repeat((int) (w.frame / 15 % 4)));
            } else if (w.phase == GameWorld.Phase.BRIEFING) {
                title = "MISSION BRIEFING";
                SolarReport r = app.report;
                lines.add("SOURCE : " + sourceLabel(r.source) + "  (" + r.flares.size() + " flares" + (r.days > 0 ? " / " + r.days + "d" : "") + ")");
                FlareEvent s = r.strongest();
                lines.add(s == null ? "SUN IS QUIET - no radiation detected." : "PEAK   : " + s.classType + " @ " + s.sourceLocation);
                if (!r.note.isEmpty()) lines.add(r.note);
                lines.add("");
                boolean any = false;
                for (BrainRegion br : BrainRegion.values()) {
                    if (brain.damage(br) >= 1) {
                        any = true;
                        lines.add(String.format("%-19s %3.0f%%  %-8s", br.label, brain.damage(br), brain.isCritical(br) ? "CRITICAL" : ""));
                    }
                }
                if (!any) lines.add("Brain nominal. Press F1 for a simulated storm.");
                if (brain.steeringReversed) {
                    lines.add("");
                    lines.add("WARNING: CONTROLS WILL BE INVERTED");
                    titleColor = new Color(255, 100, 110);
                }
                lines.add("");
                lines.add("PRESS ENTER TO LAUNCH");
            } else if (w.phase == GameWorld.Phase.GAME_OVER) {
                title = "SIGNAL LOST";
                titleColor = new Color(255, 100, 110);
                lines.add(String.format("FINAL SCORE %06d   SURVIVED %ds", (int) w.score, (int) w.elapsed));
                lines.add("");
                lines.add("ENTER to relaunch  |  F5 re-fetch NASA data");
            } else {
                return;
            }
            g.setColor(new Color(0, 0, 10, 190));
            g.fillRoundRect(70, 200, GAME_W - 140, 60 + lines.size() * 22 + 30, 16, 16);
            g.setColor(new Color(0, 230, 210, 120));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(70, 200, GAME_W - 140, 60 + lines.size() * 22 + 30, 16, 16);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
            g.setColor(titleColor);
            drawCentered(g, title, GAME_W / 2, 240);
            g.setFont(F_MONO_B);
            int y = 275;
            for (String l : lines) {
                g.setColor(l.contains("CRITICAL") || l.startsWith("WARNING") ? new Color(255, 110, 120) : new Color(200, 215, 240));
                drawCentered(g, l, GAME_W / 2, y);
                y += 22;
            }
        }
    }

    // =========================================================================
    //  RIGHT PANEL: fly-brain "navigation computer" (regions + connectome graph)
    // =========================================================================
    static final class BrainPanel extends JPanel {
        private final App app;
        private double time;

        // Stylised connectome: nodes (neuron clusters) in unit coordinates + edges (connections)
        private double[] nx, ny;
        private BrainRegion[] owner;
        private int[][] edges;

        BrainPanel(App app) {
            this.app = app;
            setPreferredSize(new Dimension(BRAIN_W, PANEL_H));
            setBackground(new Color(7, 9, 18));
            setFocusable(false);
            buildConnectome();
        }

        void tick(double dt) {
            time += dt;
        }

        /** Scatter neuron nodes inside each region's ellipses, wire near neighbours, add long-range tracts. */
        private void buildConnectome() {
            Random r = new Random(1234);                     // fixed seed => identical brain every launch
            List<double[]> pts = new ArrayList<>();
            List<BrainRegion> own = new ArrayList<>();
            for (BrainRegion reg : BrainRegion.values()) {
                List<Ellipse2D.Double> shapes = LAYOUT.get(reg);
                int perShape = reg == BrainRegion.CENTRAL_COMPLEX ? 16 : 11;
                for (Ellipse2D.Double e : shapes) {
                    for (int i = 0; i < perShape; i++) {
                        double a = r.nextDouble() * Math.PI * 2, rad = Math.sqrt(r.nextDouble()) * 0.9;
                        pts.add(new double[]{e.getCenterX() + Math.cos(a) * rad * e.width / 2,
                                e.getCenterY() + Math.sin(a) * rad * e.height / 2});
                        own.add(reg);
                    }
                }
            }
            int n = pts.size();
            nx = new double[n]; ny = new double[n]; owner = new BrainRegion[n];
            for (int i = 0; i < n; i++) { nx[i] = pts.get(i)[0]; ny[i] = pts.get(i)[1]; owner[i] = own.get(i); }

            List<int[]> es = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < n; i++) {                    // local wiring: 2 nearest same-region neighbours
                for (int pass = 0; pass < 2; pass++) {
                    int best = -1;
                    double bd = 1e9;
                    for (int j = 0; j < n; j++) {
                        if (j == i || owner[j] != owner[i] || seen.contains(key(i, j))) continue;
                        double d = Math.hypot(nx[i] - nx[j], ny[i] - ny[j]);
                        if (d < bd) { bd = d; best = j; }
                    }
                    if (best >= 0 && seen.add(key(i, best))) es.add(new int[]{i, best});
                }
            }
            // long-range tracts (feed-forward: senses -> memory -> steering -> motor)
            BrainRegion[][] tracts = {
                    {BrainRegion.OPTIC_LOBE, BrainRegion.CENTRAL_COMPLEX}, {BrainRegion.MUSHROOM_BODY, BrainRegion.CENTRAL_COMPLEX},
                    {BrainRegion.ANTENNAL_LOBE, BrainRegion.MUSHROOM_BODY}, {BrainRegion.CENTRAL_COMPLEX, BrainRegion.SUBESOPHAGEAL},
                    {BrainRegion.OPTIC_LOBE, BrainRegion.MUSHROOM_BODY}};
            for (BrainRegion[] t : tracts) {
                for (int k = 0; k < 7; k++) {
                    int a = randomNodeOf(t[0], r), b = randomNodeOf(t[1], r);
                    if (a >= 0 && b >= 0 && seen.add(key(a, b))) es.add(new int[]{a, b});
                }
            }
            edges = es.toArray(new int[0][]);
        }

        private int randomNodeOf(BrainRegion reg, Random r) {
            List<Integer> c = new ArrayList<>();
            for (int i = 0; i < owner.length; i++) if (owner[i] == reg) c.add(i);
            return c.isEmpty() ? -1 : c.get(r.nextInt(c.size()));
        }

        private static long key(int a, int b) {
            return (long) Math.min(a, b) * 100000L + Math.max(a, b);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            BrainState brain = app.brain;
            int w = getWidth(), h = getHeight();

            // faint grid
            g.setColor(new Color(20, 26, 44));
            for (int x = 0; x < w; x += 40) g.drawLine(x, 0, x, h);
            for (int y = 0; y < h; y += 40) g.drawLine(0, y, w, y);
            g.setColor(new Color(0, 230, 210, 110));
            g.drawLine(0, 0, 0, h);                                   // divider between the two halves

            g.setFont(F_TITLE);
            g.setColor(new Color(0, 230, 210));
            g.drawString("DROSOPHILA NAV-COMPUTER", 20, 28);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            g.setColor(new Color(110, 125, 160));
            g.drawString("stylised connectome schematic  //  hemibrain-inspired", 20, 46);

            final int side = 400, bx = (w - side) / 2, by = 64;
            g.setColor(new Color(14, 20, 34));
            g.fill(new RoundRectangle2D.Double(bx - 8, by - 8, side + 16, side + 16, 140, 140));
            g.setColor(new Color(40, 55, 85));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new RoundRectangle2D.Double(bx - 8, by - 8, side + 16, side + 16, 140, 140));

            for (BrainRegion r : BrainRegion.values()) drawRegion(g, r, brain, bx, by, side);
            drawConnectome(g, brain, bx, by, side);

            if (brain.steeringReversed) {                            // whole-panel glitch tear while inverted
                Random gr = new Random((long) (time * 10) * 104729L);
                if (gr.nextInt(3) == 0) {
                    g.setColor(new Color(255, 60, 80, 70));
                    g.fillRect(bx - 10, by + gr.nextInt(side), side + 20, 2 + gr.nextInt(6));
                }
            }

            int y = drawLegend(g, brain, by + side + 30);
            drawTelemetry(g, brain, y + 4);

            if (brain.steeringReversed) {                            // pulsing red frame
                boolean blink = ((int) (time * 3)) % 2 == 0;
                g.setStroke(new BasicStroke(4f));
                g.setColor(new Color(255, 30, 60, blink ? 210 : 70));
                g.drawRect(2, 2, w - 4, h - 4);
            }
            g.dispose();
        }

        private void drawRegion(Graphics2D g, BrainRegion reg, BrainState brain, int bx, int by, int side) {
            double dmg = brain.damage(reg);
            boolean crit = brain.isCritical(reg);
            for (Ellipse2D.Double e : LAYOUT.get(reg)) {
                Shape s = new Ellipse2D.Double(bx + e.x * side, by + e.y * side, e.width * side, e.height * side);
                g.setColor(regionColor(dmg, 45 + (int) (dmg * 1.2)));
                g.fill(s);
                boolean blink = ((int) (time * 5)) % 2 == 0;
                g.setStroke(new BasicStroke(crit && blink ? 3.5f : 2f));
                g.setColor(regionColor(dmg, 230));
                g.draw(s);
                if (crit) drawGlitch(g, s, reg);
            }
        }

        /** Chromatic-aberration ghosting + scanline slices, re-rolled ~14x/second. */
        private void drawGlitch(Graphics2D g0, Shape s, BrainRegion reg) {
            Graphics2D g = (Graphics2D) g0.create();
            Random gr = new Random((long) (time * 14) * 7919L + reg.ordinal());
            double jx = (gr.nextDouble() - 0.5) * 12, jy = (gr.nextDouble() - 0.5) * 4;
            g.setColor(new Color(255, 0, 60, 90));
            g.fill(AffineTransform.getTranslateInstance(jx, jy).createTransformedShape(s));
            g.setColor(new Color(0, 255, 255, 50));
            g.fill(AffineTransform.getTranslateInstance(-jx, -jy).createTransformedShape(s));
            Rectangle b = s.getBounds();
            g.clip(s);
            for (int i = 0; i < 3; i++) {
                g.setColor(new Color(255, 255, 255, 70 + gr.nextInt(60)));
                g.fillRect(b.x, b.y + gr.nextInt(Math.max(1, b.height)), b.width, 1 + gr.nextInt(3));
            }
            g.dispose();
        }

        private void drawConnectome(Graphics2D g, BrainState brain, int bx, int by, int side) {
            Random flick = new Random((long) (time * 20));
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < edges.length; i++) {
                int a = edges[i][0], b = edges[i][1];
                double da = brain.damage(owner[a]), db = brain.damage(owner[b]);
                double d = Math.max(da, db);
                if (d >= CRITICAL_DAMAGE && flick.nextInt(4) == 0) continue;     // corrupted wires flicker out
                int x1 = (int) (bx + nx[a] * side), y1 = (int) (by + ny[a] * side);
                int x2 = (int) (bx + nx[b] * side), y2 = (int) (by + ny[b] * side);
                g.setColor(regionColor(d, 85));
                g.drawLine(x1, y1, x2, y2);
                if (i % 3 == 0 && !(d >= CRITICAL_DAMAGE && flick.nextBoolean())) { // travelling action-potential dot
                    double t = (time * 0.5 + i * 0.173) % 1.0;
                    g.setColor(d >= CRITICAL_DAMAGE ? new Color(255, 120, 130) : new Color(190, 255, 250));
                    g.fillOval((int) (x1 + (x2 - x1) * t) - 2, (int) (y1 + (y2 - y1) * t) - 2, 4, 4);
                }
            }
            for (int i = 0; i < nx.length; i++) {
                g.setColor(regionColor(brain.damage(owner[i]), 255));
                g.fillOval((int) (bx + nx[i] * side) - 2, (int) (by + ny[i] * side) - 2, 5, 5);
            }
        }

        private int drawLegend(Graphics2D g, BrainState brain, int top) {
            int y = top;
            for (BrainRegion r : BrainRegion.values()) {
                double d = brain.damage(r);
                g.setFont(F_MONO_B);
                g.setColor(new Color(215, 225, 245));
                g.drawString(r.label, 20, y);
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                g.setColor(new Color(110, 125, 160));
                String eff = r.effect;
                g.drawString(eff, getWidth() - 20 - g.getFontMetrics().stringWidth(eff), y);
                g.setColor(new Color(24, 32, 52));
                g.fillRect(20, y + 6, 300, 8);
                g.setColor(regionColor(d, 255));
                g.fillRect(20, y + 6, (int) (300 * d / 100.0), 8);
                g.setFont(F_MONO_B);
                g.drawString(String.format("%3.0f%%", d), 328, y + 14);
                String st = d >= CRITICAL_DAMAGE ? "CRIT" : d >= 8 ? "WARN" : " OK ";
                g.drawString(st, 376, y + 14);
                y += 34;
            }
            return y;
        }

        private void drawTelemetry(Graphics2D g, BrainState brain, int y) {
            SolarReport r = app.report;
            g.setFont(F_MONO);
            g.setColor(new Color(150, 165, 200));
            g.drawString("NASA DONKI: " + sourceLabel(r.source) + " | flares: " + r.flares.size() + (r.days > 0 ? " /" + r.days + "d" : ""), 20, y + 4);
            FlareEvent s = r.strongest();
            g.drawString(s == null ? "Peak flare: none" : "Peak flare: " + s.classType + " @ " + s.sourceLocation + "  " + s.peakTime, 20, y + 20);
            g.setFont(F_MONO_B);
            if (brain.steeringReversed) {
                g.setColor(((int) (time * 4)) % 2 == 0 ? new Color(255, 90, 100) : new Color(190, 40, 60));
                g.drawString("STEERING: INVERTED  (x -1)", 20, y + 40);
            } else {
                g.setColor(new Color(60, 255, 150));
                g.drawString("STEERING: NORMAL  (x +1)", 20, y + 40);
            }
        }
    }

    // =========================================================================
    //  APP: wires everything together (timer loop, key handling, NASA fetch)
    // =========================================================================
    static final class App {
        final BrainState brain = new BrainState();
        final GameWorld world = new GameWorld(brain);
        final Input input = new Input();
        final NasaDonkiClient nasa;
        final boolean forceDemo;
        final int lookbackDays;

        volatile SolarReport report = SolarReport.loading();
        private EnumMap<BrainRegion, Double> baseline = new EnumMap<>(BrainRegion.class);
        private int requestSeq;
        private long lastNanos;

        final GamePanel gamePanel;
        final BrainPanel brainPanel;

        App(String apiKey, boolean forceDemo, int lookbackDays) {
            this.nasa = new NasaDonkiClient(apiKey);
            this.forceDemo = forceDemo;
            this.lookbackDays = lookbackDays;
            this.gamePanel = new GamePanel(this);
            this.brainPanel = new BrainPanel(this);
        }

        void showWindow() {
            JFrame f = new JFrame("CONNECTOME COMMANDER  -  NASA Space Apps");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setLayout(new java.awt.BorderLayout());
            f.add(gamePanel, java.awt.BorderLayout.CENTER);
            f.add(brainPanel, java.awt.BorderLayout.EAST);
            f.setResizable(false);                        // fixed size = visual stability
            f.pack();
            f.setLocationRelativeTo(null);
            f.addWindowFocusListener(new WindowAdapter() {
                @Override public void windowLostFocus(WindowEvent e) { input.reset(); }   // no stuck keys on alt-tab
            });
            f.setVisible(true);

            input.install(this::onKey);
            lastNanos = System.nanoTime();
            new javax.swing.Timer(FRAME_MS, e -> tick()).start();     // THE game loop
            fetchTelemetry();                                          // NASA call at start-up
        }

        /** One frame: update physics, animate brain, repaint both halves. */
        void tick() {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - lastNanos) / 1e9);       // clamp so a hiccup never teleports the ship
            lastNanos = now;
            if (!world.paused) world.update(dt, input);
            brainPanel.tick(dt);
            gamePanel.repaint();
            brainPanel.repaint();
        }

        void onKey(int code) {
            switch (code) {
                case KeyEvent.VK_ENTER:
                    if (world.phase == GameWorld.Phase.BRIEFING || world.phase == GameWorld.Phase.GAME_OVER) {
                        brain.setAll(baseline);                        // fresh brain state for every run
                        world.paused = false;
                        world.start();
                    }
                    break;
                case KeyEvent.VK_P:
                    if (world.phase == GameWorld.Phase.PLAYING) world.paused = !world.paused;
                    break;
                case KeyEvent.VK_F1:                                   // live-demo cheat: instant solar storm
                    applyReport(new SolarReport(Source.SIMULATED, demoFlares(), "simulated storm injected (F1)", 0), false);
                    world.toast("!! SOLAR STORM DETECTED !!", 2.5);
                    break;
                case KeyEvent.VK_F2:                                   // clear radiation
                    baseline = new EnumMap<>(BrainRegion.class);
                    brain.setAll(baseline);
                    world.toast("Radiation cleared", 1.5);
                    break;
                case KeyEvent.VK_F5:
                    if (world.phase != GameWorld.Phase.PLAYING) fetchTelemetry();
                    break;
                default:
                    break;
            }
        }

        /** Kick off the NASA request. Results arrive on a worker thread; we hop back onto the EDT. */
        void fetchTelemetry() {
            world.phase = GameWorld.Phase.LOADING;
            report = SolarReport.loading();
            brain.setAll(new EnumMap<>(BrainRegion.class));
            final int id = ++requestSeq;

            if (forceDemo) {
                applyReport(new SolarReport(Source.SIMULATED, demoFlares(), "running with --demo", 0), true);
                return;
            }
            nasa.fetchRecentFlares(lookbackDays).whenComplete((flares, err) -> SwingUtilities.invokeLater(() -> {
                if (id != requestSeq) return;                          // stale response (user hit F5 again)
                if (err != null) {
                    Throwable c = err.getCause() != null ? err.getCause() : err;
                    System.err.println("[NASA] fetch failed: " + c);
                    applyReport(new SolarReport(Source.OFFLINE_FALLBACK, demoFlares(),
                            "API unreachable - using simulated storm", 0), true);
                } else {
                    System.out.println("[NASA] " + flares.size() + " flares in the last " + lookbackDays + " days");
                    applyReport(new SolarReport(Source.LIVE, flares, "", lookbackDays), true);
                }
            }));
        }

        /** Converts a report into brain damage. If `toBriefing`, also moves to the briefing screen. */
        void applyReport(SolarReport r, boolean toBriefing) {
            report = r;
            baseline = RadiationModel.compute(r.flares);
            brain.setAll(baseline);
            if (toBriefing) world.phase = GameWorld.Phase.BRIEFING;
        }
    }

    // =========================================================================
    //  SMALL HELPERS
    // =========================================================================
    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Teal (healthy) -> amber (degrading) -> red (CRITICAL and above). Red means "this region is fried". */
    static Color regionColor(double dmg, int alpha) {
        int r, g, b;
        if (dmg >= CRITICAL_DAMAGE) {
            r = 255; g = 40; b = 60;
        } else {
            double t = dmg / CRITICAL_DAMAGE;                 // 0..1 across the warning band
            r = (int) (0 + 255 * t);
            g = (int) (220 - 30 * t);
            b = (int) (200 - 140 * t);
        }
        return new Color(r, g, b, Math.max(0, Math.min(255, alpha)));
    }

    static String sourceLabel(Source s) {
        switch (s) {
            case LIVE: return "LIVE";
            case SIMULATED: return "SIMULATED";
            case OFFLINE_FALLBACK: return "OFFLINE (simulated)";
            default: return "connecting...";
        }
    }

    static void drawCentered(Graphics2D g, String s, int cx, int y) {
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
    }

    /*
     * ------------------------------------------------------------------------
     *  WIRING IN THE REAL CONNECTOME (stretch goal for the remaining hours)
     * ------------------------------------------------------------------------
     *  The brain on screen is a schematic. To make it data-driven, the Janelia /
     *  Google "hemibrain" dataset is served through the neuPrint API. Its
     *  regions of interest (ROIs) map to our five gameplay regions roughly as:
     *
     *    Central Complex  : EB, FB, PB, NO, AB          (navigation / steering)
     *    Mushroom Body    : CA, a'L, b'L, aL, bL        (learning / memory)
     *    Antennal Lobe    : AL(R)                       (smell)
     *    Optic Lobe       : LO, LOP, ME  (in FlyWire; the hemibrain crops most of it)
     *    Subesophageal    : GNG (in FlyWire / MANC-adjacent data)
     *
     *  Idea: query neuPrint for neuron counts / synapse counts per ROI, then
     *  scale each region's node count or "HP" by it - regions with more synapses
     *  soak up more radiation. Replace buildConnectome() with real ROI-to-ROI
     *  connection weights for the long-range tracts.
     * ------------------------------------------------------------------------
     */
}
