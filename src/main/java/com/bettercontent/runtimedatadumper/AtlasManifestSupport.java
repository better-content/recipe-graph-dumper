package com.bettercontent.runtimedatadumper;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

final class AtlasManifestSupport {
    private AtlasManifestSupport() {}
    static String fingerprint(List<String> orderedKindAndKeys)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");for(String value:orderedKindAndKeys){d.update(value.getBytes(StandardCharsets.UTF_8));d.update((byte)'\n');}return HexFormat.of().formatHex(d.digest());}
    static JsonObject base(String snapshot,String fingerprint,int count){JsonObject m=new JsonObject();m.addProperty("schema","bc.quest_icon_atlas.v1");m.addProperty("snapshot_id",snapshot);m.addProperty("content_fingerprint",fingerprint);m.addProperty("tile_size",64);m.addProperty("page_size",2048);m.addProperty("planned_entry_count",count);return m;}
}
