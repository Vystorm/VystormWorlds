package at.esoren.vystormworlds;

/** A reusable visit-world recipe, not a copy of an existing world folder. */
record WorldTemplate(String id,String terrain,int seconds,String inventory,boolean enabled) {
    WorldTemplate {
        if(id==null||!id.matches("[a-z0-9_-]{1,24}")||!java.util.Set.of("flat","island").contains(terrain)
                ||seconds<10||seconds>3600||!java.util.Set.of("locked","kit").contains(inventory))throw new IllegalArgumentException("template");
    }
    String worldId(){return "vystormworlds:template/"+id;}
    String worldName(){return "vystorm_visit_"+id;}
    WorldTemplate enabled(boolean value){return new WorldTemplate(id,terrain,seconds,inventory,value);}
    long expires(long startedAt){return Math.addExact(startedAt,seconds*1000L);}
}
