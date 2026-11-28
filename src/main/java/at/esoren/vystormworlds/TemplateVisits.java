package at.esoren.vystormworlds;

import Vystorm.vystorm_core.worlds.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Delegates inventory durability and restoration to Core; ordinary world groups remain separate. */
final class TemplateVisits {
    private final WorldsPlugin plugin;private final TemplateStore store;private final LongSupplier clock;
    private final Set<String> registered=new HashSet<>();private final Set<UUID> expiredNotice=new HashSet<>();
    TemplateVisits(WorldsPlugin plugin)throws Exception{this(plugin,System::currentTimeMillis);}
    TemplateVisits(WorldsPlugin plugin,LongSupplier clock)throws Exception{
        this.plugin=plugin;this.clock=clock;store=new TemplateStore(plugin.getDataFolder().toPath().resolve("templates.yml"),plugin.core().data().files());
    }
    void command(CommandSender sender,String[] args){
        if(!sender.hasPermission("vystormworlds.admin")){send(sender,"denied");return;}
        String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"list";
        try{
            if(action.equals("list")){if(store.all().isEmpty()){send(sender,"template-none");return;}for(WorldTemplate template:store.all())show(sender,template);return;}
            if(action.equals("create")&&args.length==6){
                String id=args[2].toLowerCase(Locale.ROOT);if(store.get(id)!=null){send(sender,"template-exists");return;}
                WorldTemplate template=new WorldTemplate(id,args[3].toLowerCase(Locale.ROOT),Integer.parseInt(args[4]),args[5].toLowerCase(Locale.ROOT),true);
                store.put(template);send(sender,"template-saved","template",id);show(sender,template);return;
            }
            if((action.equals("close")||action.equals("open"))&&args.length==3){
                WorldTemplate template=store.get(args[2].toLowerCase(Locale.ROOT));if(template==null){send(sender,"template-missing");return;}
                boolean open=action.equals("open");store.put(template.enabled(open));
                if(!open&&registered.contains(template.worldId())){plugin.core().worldGenerator().worlds().unregister(plugin,template.worldId());registered.remove(template.worldId());}
                send(sender,open?"template-opened":"template-closed","template",template.id());return;
            }
        }catch(IllegalArgumentException invalid){send(sender,"template-usage");return;}
        catch(Exception failure){plugin.getLogger().warning("World-template change failed: "+failure);send(sender,"template-failed");return;}
        send(sender,"template-usage");
    }
    void visit(Player player,String id){
        if(!player.hasPermission("vystormworlds.use")){send(player,"denied");return;}
        if(id==null||id.isBlank()){for(WorldTemplate template:store.all())if(template.enabled())show(player,template);return;}
        WorldTemplate template=store.get(id.toLowerCase(Locale.ROOT));
        if(template==null||!template.enabled()){send(player,"template-missing");return;}
        try{
            if(plugin.core().worldGenerator().worlds().visits().inVisit(player.getUniqueId())||plugin.core().worldGenerator().worlds().visits().hasSnapshot(player.getUniqueId())){send(player,"template-busy");return;}
            ManagedWorld managed=plugin.core().worldGenerator().worlds().get(template.worldId()).orElse(null);
            if(managed!=null&&managed.owner()!=plugin){send(player,"template-failed");return;}
            if(managed==null)managed=plugin.core().worldGenerator().worlds().register(plugin,spec(template));
            if(managed.owner()!=plugin||managed.world()==null){send(player,"template-failed");return;}
            registered.add(template.worldId());
            VisitResult result=plugin.core().worldGenerator().worlds().visits().enter(player,template.worldId(),new Location(managed.world(),.5,64,.5));
            if(result.ok())send(player,"template-preparing","template",template.id());
            else send(player,"template-entry-failed");
        }catch(RuntimeException failure){plugin.getLogger().warning("Visit-world entry failed: "+failure);send(player,"template-failed");}
    }
    WorldSpec spec(WorldTemplate template){
        boolean kit=template.inventory().equals("kit");
        VisitPolicy policy=VisitPolicy.builder().allowCommands("worlds","vw","mvw").gameMode(GameMode.ADVENTURE)
                .lockInventory(!kit).isolateExperience(true).toolKit(player->kit?Map.of(0,new ItemStack(Material.SPYGLASS),8,new ItemStack(Material.COMPASS)):Map.of())
                .cooldownSeconds(2).build();
        return WorldSpec.builder(template.worldId(),template.worldName(),new TemplateGenerator(template.terrain()))
                .layoutHash("worlds-template-v1-"+template.terrain()).autoSave(true).spawn(new SpawnPoint(.5,64,.5,0,0))
                .borderSize(128).viewDistance(4).simulationDistance(3).difficulty(Difficulty.PEACEFUL).time(6000).lockWeather(true)
                .protectAll().adminPermission("vystormworlds.admin").policy(policy).hooks(new VisitHooks(){
                    @Override public void arrived(Player player,Visit visit){send(player,"template-arrived","template",template.id(),"seconds",template.seconds());}
                    @Override public void leaving(Player player,Visit visit,ExitReason reason){expiredNotice.remove(player.getUniqueId());}
                }).build();
    }
    void leave(Player player){
        Visit visit=plugin.core().worldGenerator().worlds().visits().visit(player.getUniqueId()).orElse(null);
        if(visit==null||!registered.contains(visit.worldId())){send(player,"template-not-visiting");return;}
        if(!plugin.core().worldGenerator().worlds().visits().exit(player,ExitReason.COMMAND))send(player,"template-exit-pending");
    }
    /** Every template, open or closed, for the admin's templates page (1.21.0). */
    List<WorldTemplate> all(){return List.copyOf(store.all());}
    /** The open templates, for the visit menu (1.20.0); {@link #visit} checks again when a player picks one. */
    List<WorldTemplate> open(){List<WorldTemplate> open=new ArrayList<>();for(WorldTemplate template:store.all())if(template.enabled())open.add(template);return open;}
    /** Whether the player is in a visit of this plugin, i.e. {@link #leave} has something to end (1.20.0). */
    boolean visiting(Player player){
        try{Visit visit=plugin.core().worldGenerator().worlds().visits().visit(player.getUniqueId()).orElse(null);return visit!=null&&registered.contains(visit.worldId());}
        catch(RuntimeException none){return false;}
    }
    void tick(){
        long now=clock.getAsLong();
        for(WorldTemplate template:store.all()){
            if(!registered.contains(template.worldId()))continue;
            for(Visit visit:List.copyOf(plugin.core().worldGenerator().worlds().visits().visits(template.worldId()))){
                if(visit.phase()!=Visit.Phase.ACTIVE||now<template.expires(visit.startedAt()))continue;
                Player player=plugin.getServer().getPlayer(visit.player());if(player==null||!player.isOnline())continue;
                if(expiredNotice.add(visit.player()))send(player,"template-timeout");
                // A cancelled return keeps Core's snapshot intact; retry on a later tick rather than restoring inside.
                plugin.core().worldGenerator().worlds().visits().exit(player,ExitReason.PLUGIN);
            }
        }
    }
    void close(){plugin.core().worldGenerator().worlds().release(plugin);registered.clear();expiredNotice.clear();}
    private void show(CommandSender sender,WorldTemplate template){
        send(sender,"template-info","template",template.id(),"terrain",plugin.lang().plain(sender,"template-terrain-"+template.terrain()),"seconds",template.seconds(),
                "inventory",plugin.lang().plain(sender,"template-inventory-"+template.inventory()),"state",plugin.lang().plain(sender,template.enabled()?"template-state-open":"template-state-closed"));
    }
    private void send(CommandSender sender,String key,Object... args){plugin.lang().send(sender,key,args);}
}
