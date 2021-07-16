package skadistats.clarity.examples.camaraderousstats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skadistats.clarity.io.Util;
import skadistats.clarity.model.EngineId;
import skadistats.clarity.model.Entity;
import skadistats.clarity.model.FieldPath;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.runner.ControllableRunner;
import skadistats.clarity.source.MappedFileSource;
import skadistats.clarity.util.TextTable;

import java.io.IOException;
import skadistats.clarity.Clarity;
import skadistats.clarity.wire.common.proto.Demo.CDemoFileInfo;
import skadistats.clarity.wire.common.proto.Demo.CGameInfo.CDotaGameInfo.CPlayerInfo;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.HashMap;
import java.util.Date;
import java.util.TimeZone;
import java.text.DateFormat;
import java.text.SimpleDateFormat;


@UsesEntities
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class.getPackage().getClass());

    private final ControllableRunner runner;

    public Main(String fileName) throws IOException, InterruptedException {
        runner = new ControllableRunner(new MappedFileSource(fileName)).runWith(this);
        runner.seek(runner.getLastTick());
        runner.halt();
    }

    private TextTable buildScoreboard() {
        boolean isSource1 = runner.getEngineType().getId() == EngineId.SOURCE1;
        boolean isEarlyBetaFormat = !isSource1 && getEntity("PlayerResource").getDtClass().getFieldPathForName("m_vecPlayerData") == null;
        if (isSource1 || isEarlyBetaFormat) {
            return buildTableWithColumns(
                    new DefaultResolver<Integer>("PlayerResource", "m_iPlayerTeams.%i"),
                    new ColumnDef("Name", new DefaultResolver<String>("PlayerResource", "m_iszPlayerNames.%i")),
                    new ColumnDef("Level", new DefaultResolver<Integer>("PlayerResource", "m_iLevel.%i")),
                    new ColumnDef("K", new DefaultResolver<Integer>("PlayerResource", "m_iKills.%i")),
                    new ColumnDef("D", new DefaultResolver<Integer>("PlayerResource", "m_iDeaths.%i")),
                    new ColumnDef("A", new DefaultResolver<Integer>("PlayerResource", "m_iAssists.%i")),
                    new ColumnDef("Gold", new DefaultResolver<Integer>("PlayerResource", (isSource1 ? "EndScoreAndSpectatorStats." : "") + "m_iTotalEarnedGold.%i")),
                    new ColumnDef("LH", new DefaultResolver<Integer>("PlayerResource", "m_iLastHitCount.%i")),
                    new ColumnDef("DN", new DefaultResolver<Integer>("PlayerResource", "m_iDenyCount.%i"))
            );
        } else {
            return buildTableWithColumns(
                    new DefaultResolver<Integer>("PlayerResource", "m_vecPlayerData.%i.m_iPlayerTeam"),
                    new ColumnDef("Name", new DefaultResolver<String>("PlayerResource", "m_vecPlayerData.%i.m_iszPlayerName")),
                    new ColumnDef("Level", new DefaultResolver<Integer>("PlayerResource", "m_vecPlayerTeamData.%i.m_iLevel")),
                    new ColumnDef("K", new DefaultResolver<Integer>("PlayerResource", "m_vecPlayerTeamData.%i.m_iKills")),
                    new ColumnDef("D", new DefaultResolver<Integer>("PlayerResource", "m_vecPlayerTeamData.%i.m_iDeaths")),
                    new ColumnDef("A", new DefaultResolver<Integer>("PlayerResource", "m_vecPlayerTeamData.%i.m_iAssists")),
                    new ColumnDef("Gold", new DefaultResolver<Integer>("Data%n", "m_vecDataTeam.%p.m_iTotalEarnedGold")),
                    new ColumnDef("LH", new DefaultResolver<Integer>("Data%n", "m_vecDataTeam.%p.m_iLastHitCount")),
                    new ColumnDef("DN", new DefaultResolver<Integer>("Data%n", "m_vecDataTeam.%p.m_iDenyCount"))
            );
        }
    }

    private TextTable buildTableWithColumns(ValueResolver<Integer> teamResolver, ColumnDef... columnDefs) {
        TextTable.Builder b = new TextTable.Builder();
        for (int c = 0; c < columnDefs.length; c++) {
            b.addColumn(columnDefs[c].name, c == 0 ? TextTable.Alignment.LEFT : TextTable.Alignment.RIGHT);
        }
        TextTable table = b.build();

        int team = 0;
        int pos = 0;
        int r = 0;

        for (int idx = 0; idx < 256; idx++) {
            try {
                int newTeam = teamResolver.resolveValue(idx, team, pos);
                if (newTeam != team) {
                    team = newTeam;
                    pos = 0;
                } else {
                    pos++;
                }
            } catch (Exception e) {
                // when the team resolver throws an exception, this was the last index there was
                break;
            }
            if (team != 2 && team != 3) {
                continue;
            }
            for (int c = 0; c < columnDefs.length; c++) {
                table.setData(r, c, columnDefs[c].resolver.resolveValue(idx, team, pos));
            }
            r++;
        }

        // System.out.println(table);
        return table;
    }

    private String getEngineDependentEntityName(String entityName) {
        switch (runner.getEngineType().getId()) {
            case SOURCE1:
                return "DT_DOTA_" + entityName;
            case SOURCE2:
                return "CDOTA_" + entityName;
            default:
                throw new RuntimeException("invalid engine type");
        }
    }

    private String getTeamName(int team) {
        switch(team) {
            case 2:
                return "Radiant";
            case 3:
                return "Dire";
            default:
                return "";
        }
    }

    private Entity getEntity(String entityName) {
        return runner.getContext().getProcessor(Entities.class).getByDtName(getEngineDependentEntityName(entityName));
    }

    private class ColumnDef {
        private final String name;
        private final ValueResolver<?> resolver;

        public ColumnDef(String name, ValueResolver<?> resolver) {
            this.name = name;
            this.resolver = resolver;
        }
    }

    private interface ValueResolver<V> {
        V resolveValue(int index, int team, int pos);
    }

    private class DefaultResolver<V> implements ValueResolver<V> {
        private final String entityName;
        private final String pattern;

        public DefaultResolver(String entityName, String pattern) {
            this.entityName = entityName;
            this.pattern = pattern;
        }

        @Override
        public V resolveValue(int index, int team, int pos) {
            String fieldPathString = pattern
                    .replaceAll("%i", Util.arrayIdxToString(index))
                    .replaceAll("%t", Util.arrayIdxToString(team))
                    .replaceAll("%p", Util.arrayIdxToString(pos));
            String compiledName = entityName.replaceAll("%n", getTeamName(team));
            Entity entity = getEntity(compiledName);
            FieldPath fieldPath = entity.getDtClass().getFieldPathForName(fieldPathString);
            return entity.getPropertyForFieldPath(fieldPath);
        }
    }


    public static void main(String[] args) throws Exception {
        String replayFile = args[0];
        CDemoFileInfo info = Clarity.infoForFile(replayFile);
        TextTable scoreTable = new Main(replayFile).buildScoreboard();

        HashMap<String, HashMap<String, String>> playerReplayStats = new HashMap<String, HashMap<String, String>>();
        for (int ii = 0; ii < scoreTable.getRowCount(); ii++ ) {
            String name = (String) scoreTable.getData(ii, 0);
            playerReplayStats.put(name, new HashMap<String, String>());
            playerReplayStats.get(name).put("level", Integer.toString( (int) scoreTable.getData(ii, 1)));
            playerReplayStats.get(name).put("kills", Integer.toString( (int) scoreTable.getData(ii, 2)));
            playerReplayStats.get(name).put("deaths", Integer.toString( (int) scoreTable.getData(ii, 3)));
            playerReplayStats.get(name).put("assists", Integer.toString( (int) scoreTable.getData(ii, 4)));
            playerReplayStats.get(name).put("gold", Integer.toString( (int) scoreTable.getData(ii, 5)));
            playerReplayStats.get(name).put("lastHits", Integer.toString( (int) scoreTable.getData(ii, 6)));
            playerReplayStats.get(name).put("denies", Integer.toString( (int) scoreTable.getData(ii, 7)));
        }

        List<CPlayerInfo> players = info.getGameInfo().getDota().getPlayerInfoList();
        for (CPlayerInfo player : players) {
            String name = player.getPlayerName();
            playerReplayStats.get(name).put("matchID", Long.toString(info.getGameInfo().getDota().getMatchId()));
            playerReplayStats.get(name).put("endTime", Integer.toString(info.getGameInfo().getDota().getEndTime()));
            playerReplayStats.get(name).put("steamID", Long.toString(player.getSteamid()));
            playerReplayStats.get(name).put("heroName", player.getHeroName());
            playerReplayStats.get(name).put("didWin", Boolean.toString(info.getGameInfo().getDota().getGameWinner() == player.getGameTeam()));
        }

        System.out.println(String.format("Writing %s-stats.tsv ...", args[0]));
        try (OutputStream out = new FileOutputStream(String.format("%s-stats.tsv", args[0]))) {
            String header = String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                "matchID",
                "date",
                "dateLong",
                "endTime",
                "playerName",
                "steamID",
                "heroName",
                "didWin",
                "kills",
                "deaths",
                "assists",
                "level",
                "gold",
                "lastHits",
                "denies"
            );
            out.write(header.getBytes());
            for (HashMap.Entry<String, HashMap<String, String>> entry : playerReplayStats.entrySet()) {
                String playerName = entry.getKey();
                HashMap<String, String> playerData = entry.getValue();

                DateFormat formatFull = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
                formatFull.setTimeZone(TimeZone.getTimeZone("PST"));
                DateFormat formatShort = new SimpleDateFormat("yyyy-MM-dd");
                formatShort.setTimeZone(TimeZone.getTimeZone("PST"));

                String tsvRow = String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                    playerData.get("matchID"),
                    formatShort.format(new Date(Long.parseLong(playerData.get("endTime")) * 1000L)),
                    formatFull.format(new Date(Long.parseLong(playerData.get("endTime")) * 1000L)),
                    playerData.get("endTime"),
                    playerName,
                    playerData.get("steamID"),
                    playerData.get("heroName").replace("npc_dota_hero_", ""),
                    playerData.get("didWin"),
                    playerData.get("kills"),
                    playerData.get("deaths"),
                    playerData.get("assists"),
                    playerData.get("level"),
                    playerData.get("gold"),
                    playerData.get("lastHits"),
                    playerData.get("denies")
                );
                out.write(tsvRow.getBytes());
            }
        }
    }
}
