package de.erdbeerbaerlp.curseforgeBot;

import com.therandomlabs.curseapi.CurseAPI;
import com.therandomlabs.curseapi.CurseException;
import com.therandomlabs.curseapi.file.CurseFile;
import com.therandomlabs.curseapi.file.CurseFiles;
import com.therandomlabs.curseapi.project.CurseProject;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.TextChannel;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

public class CurseforgeUpdateThread extends Thread {
    private final CurseProject proj;
    private String channelID;
    private String roleID = "";

    CurseforgeUpdateThread(String id) throws CurseException {
        if (id.contains(";;")) {
            String[] ids = id.split(";;");
            channelID = ids[1];
            if (ids.length == 3) {
                System.out.println(ids.length);
                roleID = ids[2];
            }
        } else {
            roleID = Main.cfg.mentionRole;
            channelID = Main.cfg.DefaultChannel;
        }
        final Optional<CurseProject> project = CurseAPI.project(Integer.parseInt(id.split(";;")[0]));
        if (!project.isPresent()) throw new CurseException("Project not found");
        proj = project.get();
        setName("Curseforge Update Detector for " + proj.name() + " (ID: " + proj.id() + ")");
        Main.threads.add(this);
    }

    @Override
    public void run() {
        TextChannel channel = Main.jda.getTextChannelById(channelID);
        //noinspection ConstantConditions
        Role role = roleID.isEmpty() ? null : channel.getGuild().getRoleById(roleID);
        try {
            String projName = proj.name();
            CurseFiles<CurseFile> projFiles = proj.files();
            int newestFileId = projFiles.first().id();
            
            while (true) {
                try {
                    System.out.println("<" + projName + "> Cached: " + Main.cache.get(projName) + " Newest:" + newestFileId);
                    if (Main.cfg.isNewFile(projName, newestFileId)) {
                        if (Main.cfg.sendAllUpdates || (!Collections.disjoint(Main.cfg.updateVersions, EmbedMessage.getGameVersionsAsList(proj)))) {
                            if (role != null) {
                                EmbedMessage.sendPingableUpdateNotification(role, channel, proj);
                            } else EmbedMessage.sendUpdateNotification(channel, proj);
                        }
                        Main.cache.put(projName, newestFileId);
                        Main.cacheChanged = true;
                    }
                    sleep(TimeUnit.SECONDS.toMillis(Main.cfg.pollingTime));
                    projFiles = proj.refreshFiles();
                    newestFileId = projFiles.first().id();
                } catch (InterruptedException | CurseException ignored) {
                }
            }
        } catch (CurseException ignored) {
            ignored.printStackTrace();
        }
    }
}
