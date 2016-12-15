package org.grouplens.samantha.server.reinforce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import org.grouplens.samantha.modeler.dao.EntityDAO;
import org.grouplens.samantha.modeler.dao.EntityListDAO;
import org.grouplens.samantha.modeler.featurizer.GroupedEntityList;
import org.grouplens.samantha.server.common.JsonHelpers;
import org.grouplens.samantha.server.config.SamanthaConfigService;
import org.grouplens.samantha.server.exception.BadRequestException;
import org.grouplens.samantha.server.indexer.*;
import org.grouplens.samantha.server.io.RequestContext;
import org.grouplens.samantha.server.retriever.RetrieverUtilities;
import play.Configuration;
import play.inject.Injector;
import play.libs.Json;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

//TODO: this is just an experimental indexer
public class UserReturnCSVIndexer extends AbstractIndexer {
    private final int reinforceThreshold = 7 * 24 * 3600;
    private final CSVFileIndexer indexer;
    private final String timestampField;
    private final List<String> dataFields;
    private final String daoNameKey;
    private final String daoName;
    private final String filesKey;
    private final String separatorKey;
    private final String rewardKey;
    private final String userIdKey;
    private final String sessionIdKey;
    private final String filePath;
    private final String filePathKey;

    //TODO: a grouped csv indexer and UserReturn indexer inherits from that. Currently, an in-memory temporary solution
    public UserReturnCSVIndexer(SamanthaConfigService configService,
                                Configuration config, Injector injector, Configuration daoConfigs,
                                String daoConfigKey, String filePathKey,
                                String timestampField, List<String> dataFields,
                                String daoNameKey, String daoName, String filesKey,
                                String rewardKey, String userIdKey, String sessionIdKey, String filePath,
                                String separatorKey, CSVFileIndexer indexer) {
        super(config, configService, daoConfigs, daoConfigKey, injector);
        this.indexer = indexer;
        this.filePathKey = filePathKey;
        this.timestampField = timestampField;
        this.dataFields = dataFields;
        this.daoName = daoName;
        this.daoNameKey = daoNameKey;
        this.filesKey = filesKey;
        this.separatorKey = separatorKey;
        this.rewardKey = rewardKey;
        this.userIdKey = userIdKey;
        this.sessionIdKey = sessionIdKey;
        this.filePath = filePath;
    }

    private double rewardFunc(double returnTime) {
        if (returnTime == 0) {
            return 1.0;
        }
        return Math.min(1.0, 24 * 3600 / returnTime);
    }

    private double getReward(double lastTime, double maxTime, double newTime) {
        if (newTime - lastTime > reinforceThreshold) {
            if (maxTime - lastTime < reinforceThreshold) {
                return -1.0;
            }
            return 0.0;
        } else {
            return rewardFunc(newTime - lastTime);
        }
    }

    public ObjectNode getIndexedDataDAOConfig(RequestContext requestContext) {
        EntityDAO data = indexer.getEntityDAO(requestContext);
        Map<String, List<ObjectNode>> user2acts = new HashMap<>();
        int maxTime = 0;
        while (data.hasNextEntity()) {
            ObjectNode entity = data.getNextEntity();
            String userId = entity.get(userIdKey).asText();
            List<ObjectNode> acts = user2acts.getOrDefault(userId, new ArrayList<>());
            user2acts.put(userId, acts);
            acts.add(entity);
            int tstamp = entity.get(timestampField).asInt();
            if (tstamp > maxTime) {
                maxTime = tstamp;
            }
        }
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
            IndexerUtilities.writeOutHeader(dataFields, writer,
                    indexer.getSeparator());
            Comparator<ObjectNode> comparator = RetrieverUtilities.jsonFieldOrdering(timestampField);
            for (Map.Entry<String, List<ObjectNode>> userAct : user2acts.entrySet()) {
                List<ObjectNode> acts = userAct.getValue();
                acts.sort(comparator);
                EntityDAO listDao = new EntityListDAO(acts);
                GroupedEntityList grouped = new GroupedEntityList(
                        Lists.newArrayList(sessionIdKey), listDao);
                List<ObjectNode> group = grouped.getNextGroup();
                List<ObjectNode> nextGrp;
                while ((nextGrp = grouped.getNextGroup()).size() > 0) {
                    ObjectNode lastEntity = group.get(group.size() - 1);
                    int lastTime = lastEntity.get(timestampField).asInt();
                    int newTime = nextGrp.get(nextGrp.size() - 1).get(timestampField).asInt();
                    double reward = getReward(lastTime, maxTime, newTime);
                    if (reward >= 0.0) {
                        for (ObjectNode entity : group) {
                            entity.put(rewardKey, 0.0);
                            IndexerUtilities.writeOutJson(entity, dataFields,
                                    writer, indexer.getSeparator());
                        }
                        lastEntity.put(rewardKey, reward);
                        IndexerUtilities.writeOutJson(lastEntity, dataFields,
                                writer, indexer.getSeparator());
                    }
                    group = nextGrp;
                }
            }
            writer.close();
        } catch (IOException e) {
            throw new BadRequestException(e);
        }
        ObjectNode ret = Json.newObject();
        ret.put(daoNameKey, daoName);
        String path = JsonHelpers.getOptionalString(requestContext.getRequestBody(), filePathKey, filePath);
        ret.set(filesKey, Json.toJson(Lists.newArrayList(path)));
        ret.put(separatorKey, indexer.getSeparator());
        return ret;
    }

    public void index(JsonNode documents, RequestContext requestContext) {
        indexer.index(documents, requestContext);
    }
}