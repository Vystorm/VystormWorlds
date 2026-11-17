package at.esoren.vystormworlds;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

final class TemplateStore {
    private final Path file;private Map<String,WorldTemplate> templates=new LinkedHashMap<>();
    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;

    /** @param files Core's file part, {@code api.data().files()} */
    TemplateStore(Path file,Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files)throws Exception{
        this.files=files;
        this.file=file;if(!Files.exists(file))return;
        YamlConfiguration yaml=new YamlConfiguration();yaml.load(file.toFile());
        if(yaml.getInt("schema")!=1)throw new IOException("Unsupported world-template schema");
        var root=yaml.getConfigurationSection("templates");if(root==null)throw new IOException("Missing templates");
        for(String id:root.getKeys(false)){
            var row=root.getConfigurationSection(id);if(row==null)throw new IOException("Invalid template");
            WorldTemplate template=new WorldTemplate(id,row.getString("terrain"),row.getInt("seconds"),row.getString("inventory"),row.getBoolean("enabled",true));
            if(templates.put(id,template)!=null||templates.size()>8)throw new IOException("Too many templates");
        }
    }
    WorldTemplate get(String id){return templates.get(id);}
    List<WorldTemplate> all(){return List.copyOf(templates.values());}
    void put(WorldTemplate template)throws IOException{
        Map<String,WorldTemplate> next=new LinkedHashMap<>(templates);next.put(template.id(),template);
        if(next.size()>8)throw new IllegalArgumentException("limit");
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("schema",1);yaml.createSection("templates");
        for(WorldTemplate value:next.values()){
            String p="templates."+value.id()+".";yaml.set(p+"terrain",value.terrain());yaml.set(p+"seconds",value.seconds());
            yaml.set(p+"inventory",value.inventory());yaml.set(p+"enabled",value.enabled());
        }
        Files.createDirectories(file.getParent());files.write(file,yaml.saveToString().getBytes(StandardCharsets.UTF_8));templates=next;
    }
}
