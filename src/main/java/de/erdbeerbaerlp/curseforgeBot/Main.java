package de.erdbeerbaerlp.curseforgeBot;

import com.therandomlabs.curseapi.CurseAPI;
import com.therandomlabs.curseapi.CurseException;
import com.therandomlabs.curseapi.file.CurseDependency;
import com.therandomlabs.curseapi.file.CurseDependencyType;
import com.therandomlabs.curseapi.game.CurseCategorySection;
import com.therandomlabs.curseapi.minecraft.CurseAPIMinecraft;
import com.therandomlabs.curseapi.project.CurseProject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.apache.commons.cli.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedSearchIterable;
import org.stringtree.json.JSONReader;
import org.stringtree.json.JSONValidatingReader;

import java.io.File;
import java.io.FileFilter;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Main {
    public static final Cfg cfg = new Cfg();
    static final Map<Integer, Integer> cache = new HashMap<>();
    static final int CFG_VERSION = 4;
    static final File currentDir = new File(".");
    static GitHub github;
    static ArrayList<CurseforgeUpdateThread> threads = new ArrayList<>();
    static boolean cacheGenerated = Cfg.cacheFile.exists();
    static boolean debug = false;
    static boolean useGithub = false;
    static boolean cacheChanged;
    static GHRepository repo = null;
    static JDA jda;

    public static void main(String[] args) {
        CurseAPIMinecraft.initialize();
        
        final Options o = new Options();
        o.addOption("debug", false, "Enables debug log");
        final Option token = new Option("token", true, "Provides the bot token");
        token.setRequired(cfg.BOT_TOKEN.equals("args"));
        o.addOption(token);
        final Option ghopt = new Option("github", true, "When providing this, it will store the cache on github, login info needs to be specified in the config or as argument");
        ghopt.setOptionalArg(true);
        o.addOption(ghopt);
        CommandLineParser parser = new DefaultParser();
        try {
            // parse the command line arguments
            CommandLine line = parser.parse(o, args);
            debug = line.hasOption("debug");

            System.out.println("Caught command line args!");
            useGithub = line.hasOption("github");
            System.out.println("Using github: " + useGithub);
            if (line.hasOption("token") && cfg.BOT_TOKEN.equals("args")) cfg.BOT_TOKEN = line.getOptionValue("token");
            if (useGithub && line.getOptionValue("github") != null) {
                cfg.githubToken = line.getOptionValue("github");
                System.out.println("Took github token from command line");
            }
            if (useGithub) {
                System.out.println("Logging in to github...");
                try {
                    github = GitHub.connectUsingOAuth(cfg.githubToken);
                } catch (IOException e) {
                    System.err.println("Failed to login to guthub: " + e.getMessage());
                }
                System.out.println("Attempting to use repository \"" + cfg.githubRepo + "\"");
                try {
                    final PagedSearchIterable<GHRepository> tmp = github.searchRepositories().user(github.getMyself().getLogin()).list();
                    System.out.println(tmp);
                    GHRepository rep = null;
                    System.out.println("Searching existing repos...");
                    for (final GHRepository r : tmp) {
                        System.out.println("Found repo " + r.getName());
                        if (r.getName().equals(cfg.githubRepo)) {
                            rep = r;
                            break;
                        }
                    }
                    if (rep == null) {
                        System.out.println("Generating new private repository...");
                        repo = github.createRepository(cfg.githubRepo).private_(true).description("Repository used by the Curseforge Bot to store cache externally").create();
                    } else repo = rep;
                    cacheGenerated = cfg.doesGHCacheExist();
                } catch (IOException e) {
                    System.err.println("Failed to connect to github!\n" + e.getMessage());
                }
            }
            if (cfg.BOT_TOKEN.equals("InsertHere") || cfg.DefaultChannel.equals("000000000")) {
                System.err.println("You didnt modify the config! This bot wont work without Channel ID or Token!");
                System.exit(1);
            }
            if (debug) System.out.println("Bot-Token is " + cfg.BOT_TOKEN);
            try {
                jda = JDABuilder.createLight(cfg.BOT_TOKEN)
                        .build().awaitReady();
            } catch (Exception e) {
                System.err.println("<JDA> " + e.getMessage());
                System.exit(1);
            }
            
            // Expand modpacks for convenience
            ListIterator<String> it = cfg.IDs.listIterator();
            while (it.hasNext()) {
                try {
                    String[] p = it.next().split(";;");
                    final Optional<CurseProject> project = CurseAPI.project(Integer.parseInt(p[0]));
                    if (!project.isPresent()) throw new CurseException("Project not found");
                    final CurseProject pr = project.get();
                    // Set<CurseDependency> deps = pr.files().first().dependencies();
                    CurseCategorySection cs = pr.categorySection();
                    
                    if (debug) System.out.println(
                            // "Project " + pr.name() + " has " + deps.size() + " dependencies\n" + 
                            "Project " + pr.name() + " has category section of " + cs.toString());
                    
                    // Set<CurseDependency> filteredDeps = deps.stream()
                    //         .filter(d -> d.type() == CurseDependencyType.INCLUDE)
                    //         .collect(Collectors.toSet());
                    if (//filteredDeps.isEmpty() && 
                            cs.gameID() != 432 || // Check for MC
                            (cs.id() != 11 && // Check for modpacks section
                            cs.id() != 4471) // Not sure which id it returns
                    ) continue;
                    
                    System.out.println("Expanding modpack " + pr.name());
                    // Remove modpack id from list
                    it.remove();
                    // Add modpack deps to list
                    /* Modpacks don't use dependencies
                    for (CurseDependency dep : filteredDeps) {
                        if (debug) System.out.println("Adding modpack dep " + dep.project().name());
                        p[0] = String.valueOf(dep.projectID());
                        it.add(String.join(";;", p));
                    }
                    */
                    File modpackZip = new File("latestModpack" + pr.id() + "-" + pr.files().first().id() + ".zip");
                    if (!modpackZip.exists()) {
                        System.out.println("Removing old modpack zips for " + pr.id());
                        FileFilter ff = new WildcardFileFilter("latestModpack" + pr.id() + "*.zip");
                        File[] files = currentDir.listFiles(ff);
                        for (File deleteFile : files) {
                            deleteFile.delete();
                        }
                        
                        System.out.println("Downloading latest modpack zip for " + pr.id());
                        URL modpackURL = pr.files().first().downloadURL().url();
                        FileUtils.copyURLToFile(modpackURL, modpackZip, 20000, 1200000);
                    }
                    
                    ZipFile zf = new ZipFile(modpackZip);
                    
                    for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                        ZipEntry entry = (ZipEntry) e.nextElement();
                        if (debug) System.out.println("zip entry name: " + entry.getName());
                        if (!entry.isDirectory() && 
                                FilenameUtils.getName(entry.getName()).equals("manifest.json")) {
                            if (debug) System.out.println("manifest.json found");
                            InputStream in = zf.getInputStream(entry);
                            String manifest = IOUtils.toString(in, StandardCharsets.UTF_8);
                            // if (debug) System.out.println(manifest);
                            
                            JSONReader reader = new JSONValidatingReader();
                            Object manifestObj = reader.read(manifest);
                            if (manifestObj instanceof Map) {
                                if (debug) System.out.println("manifest map found");
                                @SuppressWarnings("unchecked") Map<Object, Object> manifestMap = (Map<Object, Object>) manifestObj;
                                if (manifestMap.containsKey("files") && manifestMap.get("files") instanceof List) {
                                    if (debug) System.out.println("files array found");
                                    @SuppressWarnings("unchecked") List<Object> filesList = (List<Object>) manifestMap.get("files");
                                    for (Object obj : filesList) {
                                        if (obj instanceof Map) {
                                            // if (debug) System.out.println("file obj found");
                                            @SuppressWarnings("unchecked") Map<Object, Object> fileMap = (Map<Object, Object>) obj;
                                            if (fileMap.containsKey("projectID") && fileMap.get("projectID") instanceof Long) {
                                                p[0] = ((Long) fileMap.get("projectID")).toString();
                                                if (debug) System.out.println("Adding modpack dep id " + p[0] + " in modpack " + pr.id());
                                                it.add(String.join(";;", p));
                                            }
                                        }
                                    }
                                }
                            }
                            
                            break;
                        }
                    }
                } catch (IOException | CurseException e) {
                    e.printStackTrace();
                }
            }
            
            if (!cacheGenerated) {
                System.out.println("Generating cache...");
                for (String p : cfg.IDs) {
                    try {
                        final Optional<CurseProject> project = CurseAPI.project(Integer.parseInt(p.split(";;")[0]));
                        if (!project.isPresent()) throw new CurseException("Project not found");
                        final CurseProject pr = project.get();
                        cache.put(pr.id(), pr.files().first().id());
                    } catch (CurseException e) {
                        e.printStackTrace();
                    }
                }
            /*for (String p : cfg.USERs) {
                try {
                    final CurseProject pr = CurseProject.fromID(p.split(";;")[0]);
                    cache.put(pr.title(), pr.latestFile().id());
                    cfg.saveCache();
                } catch (CurseException e) {
                    e.printStackTrace();
                }
            }*/
                cfg.saveCache();
                System.out.println("Done!");
            } else {
                cfg.loadCache();
                if (cacheChanged) cfg.saveCache();
            }
            for (String p : cfg.IDs) {
                try {
                    if (debug) System.out.println("Starting update thread " + p);
                    new CurseforgeUpdateThread(p).start();
                    Thread.sleep(100);
                } catch (CurseException e) {
                    e.printStackTrace();
                }
            }
        /*for (String p : cfg.USERs) {
            try {
                // No way to do this *now*
                new CurseforgeUpdateThread(p).start();
            } catch (CurseException e) {
                e.printStackTrace();
            }
        }*/
            while (true) {
                try {
                    if (debug) System.out.println("Sleeping for " + (long) (TimeUnit.SECONDS.toMillis(cfg.pollingTime)) + "ms.");
                    Thread.sleep((long) (TimeUnit.SECONDS.toMillis(cfg.pollingTime)));
                    System.out.println("MAIN Tick");
                    if (cacheChanged) {
                        System.out.println("Saving changed caches...");
                        cacheChanged = false;
                        cfg.saveCache();
                    }
                    cfg.loadCache();
                } catch (InterruptedException e) {
                    System.out.println("Main Thread interrupted!");
                }
            }
        } catch (ParseException exp) {
            System.err.println(exp.getMessage());
        }
    }
}
