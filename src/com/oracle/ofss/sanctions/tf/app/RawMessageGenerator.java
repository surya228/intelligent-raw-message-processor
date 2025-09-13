package com.oracle.ofss.sanctions.tf.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class RawMessageGenerator {
public static int generateRawMessage(BlockingQueue<File> queue) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("\n=============================================================");
        System.out.println("                RAW MESSAGE GENERATOR STARTED                ");
        System.out.println("=============================================================");
        Connection connection = null;
        JSONArray rawMessageJsonArray = null;
        ResultSet rs = null;

        try {

            Properties props = new Properties();

            try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("Error reading properties file: " + e.getMessage());
                throw e;
            }


            String watchlistType = props.getProperty(Constants.WATCHLIST_TYPE);
            String tableName = Constants.TABLE_WL_MAP.get(watchlistType);
            String tagName = props.getProperty(Constants.TAGNAME);
            String webService = props.getProperty(Constants.WEBSERVICE);
            String tansactionService = props.getProperty(Constants.TRANSACTION_SERVICE);
            String webserviceId = props.getProperty(Constants.WEBSERVICE_ID);
            boolean isStopwordEnabled = Constants.YES.equalsIgnoreCase(props.getProperty("stopword"));
            boolean isSynonymEnabled = Constants.YES.equalsIgnoreCase(props.getProperty("synonym"));

            try {
                validateConfigProperties(watchlistType, webserviceId, isStopwordEnabled, isSynonymEnabled);
                System.out.println("Config Properties Validation Passed.");
            } catch (Exception e){
                System.out.println("Config Properties Validation Failed.");
                throw new Exception(e);
            }

            String srcFile = loadJsonFromFile(Constants.SOURCE_FILE_PATH);
            System.out.println("srcFile: "+srcFile);


            connection = SQLUtility.getDbConnection();
            rs = prepareQueryAndGetTableData(connection, props, tableName);



            rawMessageJsonArray = generateRawMessageJsonArray(rs,props,srcFile,tableName,tagName,webserviceId,watchlistType,isStopwordEnabled,isSynonymEnabled);

            if(rawMessageJsonArray.length()>0){
                writeJsonAsExcelFile(rawMessageJsonArray,tansactionService,tagName,webService,watchlistType, queue);
                writeRawMessagesToJsonFile(rawMessageJsonArray);
            }

            System.out.println("\n=============================================================");
            System.out.println("                 RAW MESSAGE GENERATOR ENDED                 ");
            System.out.println("=============================================================");
            long endTime = System.currentTimeMillis();

            System.out.println("Time taken by Raw Message Generator: " + (endTime - startTime) / 1000L + " seconds");

        } catch (Exception e) {
            e.printStackTrace();
} finally {
    if (rs != null) {
        rs.close();
    }
    if (connection != null) {
        try {
            connection.close();
            System.out.println("Connection closed.");
        } catch (SQLException e) {
            System.err.println("Failed to close the connection:");
            e.printStackTrace();
        }
    }
}
return rawMessageJsonArray != null ? rawMessageJsonArray.length() : 0;
    }

    private static boolean validateConfigProperties(String watchlistType, String webserviceId, boolean isStopwordEnabled, boolean isSynonymEnabled) throws Exception {
        // Check if both synonym and stopword are enabled
        if (isSynonymEnabled && isStopwordEnabled) {
            throw new Exception("Cannot enable both synonym and stopword at the same time. Only one should be enabled.");
        }

        // If neither synonym nor stopword is enabled, return true
        if (!isSynonymEnabled && !isStopwordEnabled) {
            return true;
        }

        // Define unsupported webserviceIds
        List<String> unsupportedWebserviceIds = Arrays.asList("2", "5", "6");
        if (unsupportedWebserviceIds.contains(webserviceId)) {
            throw new Exception("Synonym or Stopword are not supported for "+ Constants.WEBSERVICE_MAP.get(webserviceId));
        }

        // Define specific validation rules for webserviceId "3" and "4"
        if ("3".equalsIgnoreCase(webserviceId)) {
            validateWebserviceId3(watchlistType, isStopwordEnabled);
        } else if ("4".equalsIgnoreCase(webserviceId)) {
            validateWebserviceId4(watchlistType, isSynonymEnabled);
        }

        return true;
    }

    private static void validateWebserviceId3(String watchlistType, boolean isStopwordEnabled) throws Exception {
        if ("CITY".equalsIgnoreCase(watchlistType)) {
            throw new Exception("Synonym or Stopword are not supported for City");
        }
        if (isStopwordEnabled && "COUNTRY".equalsIgnoreCase(watchlistType)) {
            throw new Exception("Stopword is not supported for Country");
        }
    }

    private static void validateWebserviceId4(String watchlistType, boolean isSynonymEnabled) throws Exception {
        if ("IDENTIFIER".equalsIgnoreCase(watchlistType)) {
            throw new Exception("Synonym or Stopword are not supported for Narrative Identifier");
        }
        if (isSynonymEnabled) {
            List<String> unsupportedWatchlistTypes = Arrays.asList("CITY", "GOODS", "PORT", "STOP_KEYWORDS");
            if (unsupportedWatchlistTypes.contains(watchlistType.toUpperCase())) {
                throw new Exception("Synonym is not supported for Narrative " + watchlistType);
            }
        }
    }


    private static ResultSet prepareQueryAndGetTableData(Connection connection, Properties props, String tableName) throws Exception {
        PreparedStatement pst = null;
        ResultSet rs = null;
        String filter="";
        if(props.containsKey(Constants.WHERE_CLAUSE)){
            filter = " where "+ props.get(Constants.WHERE_CLAUSE);
        }

        String query = "select * from "+tableName+" "+filter;
        System.out.println("SQL Query generated:: "+query);
        try {
            pst = connection.prepareStatement(query);
            rs = pst.executeQuery();
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Something went wrong while preparing Query: ", e);
        }
        return rs;
    }

public static JSONArray generateRawMessageJsonArray(ResultSet rs, Properties props, String srcFile, String tableName, String tagName, String webserviceId, String watchlistType, boolean isStopwordEnabled, boolean isSynonymEnabled) throws Exception {
        JSONArray jsonArray = new JSONArray();
        int maxIndex = getMaxIndex(props, Constants.REPLACE_SRC);
        String temp;
        int updatedCount = 0;

        List<Object[]> stopwords = null;
        Map<String, Map<String, String>> synonymMap = null;

        if (isStopwordEnabled) {
            Connection connection = SQLUtility.getDbConnection();
            stopwords = getRelevantStopwords(props, connection);
            connection.close();
        }

        if (isSynonymEnabled) {
            Connection connection = SQLUtility.getDbConnection();
            synonymMap = loadSynonyms(connection, watchlistType);
            connection.close();
        }

        int cnt=0;
        while(rs.next()) {
            temp=srcFile;
            if(temp != null) {
                for (int i = 1; i <= maxIndex; i++) {
                    String srcKey = Constants.REPLACE_SRC+"[" + i + "]";
                    String targetColumnKey = Constants.REPLACE_TARGET_COLUMN+"[" + i + "]";

                    String token = props.getProperty(srcKey);
                    String targetColumn = props.getProperty(targetColumnKey);
                    String tokenValue = rs.getString(targetColumn);
                    if(tokenValue==null) break;
                    String identifierToken =  props.getProperty(Constants.REPLACE_SRC+"[0]");
                    String identifierTargetColumn = props.getProperty(Constants.REPLACE_TARGET_COLUMN+"[0]");
                    String identifierToBeReplaced = rs.getString(identifierTargetColumn);
                    String uid = rs.getString(Constants.NUID);

                    String[] toBeReplacedValues = tokenValue.split(";");

                    for(String toBeReplaced : toBeReplacedValues) {
                        if (isSynonymEnabled) {
                            List<Map<String, Object>> variantsWithInfo = generateSynonymVariantsWithInfo(toBeReplaced, synonymMap, props);
                            for (Map<String, Object> info : variantsWithInfo) {
                                String variant = (String) info.get("variant");
                                String lookupIds = (String) info.get("lookupIds");
                                String lookupValueIds = (String) info.get("lookupValueIds");
                                temp = srcFile;
                                updatedCount = createRawMsg(temp, variant, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, -2, uid, tagName, webserviceId, lookupIds, lookupValueIds);
                            }
                        } else {
                            if (!isStopwordEnabled) {
                                // 0 ced -> exact
                                updatedCount = createRawMsg(temp, toBeReplaced, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 0, uid, tagName, webserviceId, "NA", "NA");

                                if (props.getProperty(Constants.CED1).equalsIgnoreCase(Constants.YES)) { // 1 ced
                                    List<String> oneCedList = generate1CedVariants(toBeReplaced);
                                    for (String value : oneCedList) {
                                        temp = srcFile;
                                        updatedCount = createRawMsg(temp, value, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 1, uid, tagName, webserviceId, "NA", "NA");
                                    }
                                }

                                if (props.getProperty(Constants.CED2).equalsIgnoreCase(Constants.YES)) { // 2 ced
                                    List<String> twoCedList = generate2CedVariants(toBeReplaced);
                                    for (String value : twoCedList) {
                                        temp = srcFile;
                                        updatedCount = createRawMsg(temp, value, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 2, uid, tagName, webserviceId, "NA", "NA");
                                    }
                                }

                                if (props.getProperty(Constants.CED3).equalsIgnoreCase(Constants.YES)) { // 3 ced
                                    List<String> threeCedList = generate3CedVariants(toBeReplaced);
                                    for (String value : threeCedList) {
                                        temp = srcFile;
                                        updatedCount = createRawMsg(temp, value, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 3, uid, tagName, webserviceId, "NA", "NA");
                                    }
                                }
                            }

                            // Stopword variants
                            if (isStopwordEnabled && stopwords != null && !stopwords.isEmpty()) {
                                for (Object[] pair : stopwords) {
                                    String stop = (String) pair[0];
                                    String lookupId = (String) pair[1];
                                    String lookupValueId = (String) pair[2];
                                    List<String> variants = generateStopwordVariants(toBeReplaced, stop);
                                    for (String variant : variants) {
                                        String variantTemp = srcFile;
                                        updatedCount = createRawMsg(variantTemp, variant, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, -1, uid, tagName, webserviceId, lookupId, lookupValueId);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            cnt++;
        }
        System.out.println("No. of rows selected from Watchlist:: "+ cnt);
        System.out.println("No. of raw message created by Generator:: "+ updatedCount);
        return jsonArray;

    }

    public static int createRawMsg(String temp, String value, String identifierToBeReplaced,
                      String token, String targetColumn, String identifierToken,
                      String tableName, JSONArray jsonArray, int updatedCount, String originalValue, int ced, String uid,
                                   String tagName, String webserviceId, String lookupIds, String lookupValueIds){
        if (value != null) {
            System.out.println("toBeReplaced: " + value + " originalValue: " + originalValue + "  token: " + token + "  column: "+ targetColumn + "  identifier: "+ identifierToBeReplaced + " ced: "+ ced);
            identifierToBeReplaced = Constants.IDEN_PREFIX+identifierToBeReplaced;
            temp = temp.replace(token, value);
            temp = temp.replace(identifierToken,identifierToBeReplaced);

            JSONObject tempJson = new JSONObject(temp);
            JSONObject additionalData = tempJson.getJSONObject(Constants.ADDITIONAL_DATA);
            additionalData.put(Constants.TABLE, tableName);
            additionalData.put(Constants.UID,uid);
            additionalData.put(Constants.COLUMN, targetColumn);
            additionalData.put(Constants.TOKEN, token);
            additionalData.put(Constants.VALUE, value);
            additionalData.put(Constants.ORIGINAL_VALUE, originalValue);
            additionalData.put(Constants.CED, ced);
            additionalData.put(Constants.TAGNAME,tagName);
            additionalData.put(Constants.WEBSERVICE_ID,webserviceId);
            additionalData.put(Constants.IDEN_TOKEN, identifierToken);
            additionalData.put(Constants.IDEN_VALUE, identifierToBeReplaced);
            additionalData.put(Constants.IS_STOPWORD_PRESENT, (ced == -1 ? "Y" : "N"));
            additionalData.put("isSynonymPresent", (ced == -2 ? "Y" : "N"));
            additionalData.put(Constants.LOOKUP_ID, lookupIds);
            additionalData.put(Constants.LOOKUP_VALUE_ID, lookupValueIds);
            jsonArray.put(tempJson);

            updatedCount++;
        }
        return updatedCount;
    }
    public static List<String> generate1CedVariants(String input) {
        List<String> variants = new ArrayList<>();
        int len = input.length();

        // Delete
        if (len >= 1) variants.add(input.substring(1)); // remove first
        if (len >= 3) variants.add(input.substring(0, len / 2) + input.substring((len / 2) + 1)); // remove middle
        if (len >= 1) variants.add(input.substring(0, len - 1)); // remove last

//        // Insert
//        variants.add(INSERT_CHAR + input); // insert at start
//        variants.add(input.substring(0, len / 2) + INSERT_CHAR + input.substring(len / 2)); // middle
//        variants.add(input + INSERT_CHAR); // insert at end

        return variants;
    }

    private static List<Object[]> getRelevantStopwords(Properties props, Connection connection) throws SQLException {
        List<Object[]> stopwords = new ArrayList<>();

        String query = "SELECT * FROM ( " +
                       "  SELECT v.V_LOOKUP_VALUES, v.N_LOOKUP_ID, v.N_LOOKUP_VALUE_ID, " +
                       "         ROW_NUMBER() OVER (PARTITION BY l.N_LOOKUP_ID ORDER BY DBMS_RANDOM.VALUE) AS rn " +
                       "  FROM fcc_idx_m_lookup l " +
                       "  JOIN FCC_IDX_M_LOOKUP_VALUES v ON l.N_LOOKUP_ID = v.N_LOOKUP_ID " +
                       "  WHERE l.F_IS_SYNONYM = 'N' " +
                       "  AND l.N_LOOKUP_ID in ("+props.getProperty("stopword.lookupIdIn")+")"+
                       " ) WHERE rn <= ?";

        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, props.getProperty("stopword.pickValuesFromEachLookup"));
        ResultSet rs = stmt.executeQuery();

        while (rs.next()) {
            String value = rs.getString("V_LOOKUP_VALUES");
            String lookupId = rs.getString("N_LOOKUP_ID");
            String lookupValueId = rs.getString("N_LOOKUP_VALUE_ID");
            stopwords.add(new Object[]{value, lookupId, lookupValueId});
        }
        rs.close();
        stmt.close();

        return stopwords;
    }

private static Map<String, Map<String, String>> loadSynonyms(Connection connection, String watchlistType) throws SQLException {
    Map<String, Map<String, String>> synonymMap = new HashMap<>();
    List<String> lookupIds = getLookupIdsForWatchlistType(watchlistType);

    if (lookupIds.isEmpty()) {
        return synonymMap; // Return empty map if no lookup IDs are specified for the watchlist type
    }

    String query = "SELECT N_LOOKUP_ID FROM fcc_idx_m_lookup WHERE F_IS_SYNONYM = 'Y' AND N_LOOKUP_ID IN (" + String.join(",", lookupIds) + ")";

    try (PreparedStatement stmt = connection.prepareStatement(query);
         ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
            String lookupId = rs.getString("N_LOOKUP_ID");
            Map<String, String> innerMap = new HashMap<>();
            String valuesQuery = "SELECT N_LOOKUP_VALUE_ID, V_LOOKUP_VALUES FROM FCC_IDX_M_LOOKUP_VALUES WHERE N_LOOKUP_ID = ?";
            try (PreparedStatement vStmt = connection.prepareStatement(valuesQuery)) {
                vStmt.setString(1, lookupId);
                try (ResultSet vRs = vStmt.executeQuery()) {
                    while (vRs.next()) {
                        String valueId = vRs.getString("N_LOOKUP_VALUE_ID");
                        String values = vRs.getString("V_LOOKUP_VALUES");
                        // Ignore (remove) newline characters in values
                        if (values != null) {
                            values = values.replaceAll("\\n", "");
                        }
                        innerMap.put(valueId, values);
                    }
                }
            }
            synonymMap.put(lookupId, innerMap);
        }
    }
    return synonymMap;
}

private static List<String> getLookupIdsForWatchlistType(String watchlistType) {
    List<String> lookupIds = new ArrayList<>();
    switch (watchlistType) {
        case "COUNTRY":
            lookupIds.add("2");
            break;
        case "WCPREM":
        case "OFAC":
        case "DJW":
        case "PRV_WL1":
        case "WCSTANDARD":
        case "EU":
        case "HMT":
        case "UN":
            lookupIds.add("1");
            lookupIds.add("3");
            lookupIds.add("6");
            break;
        // Add more cases as needed for other watchlist types
        default:
            break;
    }
    return lookupIds;
}

    private static List<Map<String, Object>> generateSynonymVariantsWithInfo(String toBeReplaced, Map<String, Map<String, String>> synonymMap, Properties props) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean multiword = "Y".equalsIgnoreCase(props.getProperty("synonym.multiword", "N"));
        boolean multipleGroups = "Y".equalsIgnoreCase(props.getProperty("synonym.multipleGroups", "N"));

        if (!multiword) {
            Set<String> usedLookupIds = new HashSet<>();
            Set<String> usedValueIds = new HashSet<>();
            List<String> alts = new ArrayList<>();
            boolean found = false;
            outer: for (Map.Entry<String, Map<String, String>> lookupEntry : synonymMap.entrySet()) {
                String lookupId = lookupEntry.getKey();
                for (Map.Entry<String, String> valueEntry : lookupEntry.getValue().entrySet()) {
                    String valueId = valueEntry.getKey();
                    String valuesStr = valueEntry.getValue();
                    String[] synonyms = valuesStr.split(",");
                    if (Arrays.asList(synonyms).contains(toBeReplaced)) {
                        found = true;
                        usedLookupIds.add(lookupId);
                        usedValueIds.add(valueId);
                        for (String alt : synonyms) {
                            if (!alt.equals(toBeReplaced) && !alts.contains(alt)) {
                                alts.add(alt);
                            }
                        }
                        if (!multipleGroups) break outer;
                    }
                }
            }
            if (!alts.isEmpty()) {
                String lids = String.join(",", usedLookupIds);
                String vids = String.join(",", usedValueIds);
                for (String alt : alts) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("variant", alt);
                    info.put("lookupIds", lids);
                    info.put("lookupValueIds", vids);
                    result.add(info);
                }
            }
        } else {
            String[] words = toBeReplaced.split("\\s+");
            List<List<String>> options = new ArrayList<>();
            List<Set<String>> lidsPer = new ArrayList<>();
            List<Set<String>> vidsPer = new ArrayList<>();
            for (String word : words) {
                List<String> wordOptions = new ArrayList<>();
                wordOptions.add(word);
                Set<String> wordLids = new HashSet<>();
                Set<String> wordVids = new HashSet<>();
                outer: for (Map.Entry<String, Map<String, String>> lookupEntry : synonymMap.entrySet()) {
                    String lookupId = lookupEntry.getKey();
                    for (Map.Entry<String, String> valueEntry : lookupEntry.getValue().entrySet()) {
                        String valueId = valueEntry.getKey();
                        String valuesStr = valueEntry.getValue();
                        String[] synonyms = valuesStr.split(",");
                        if (Arrays.asList(synonyms).contains(word)) {
                            wordLids.add(lookupId);
                            wordVids.add(valueId);
                            for (String alt : synonyms) {
                                if (!alt.equals(word) && !wordOptions.contains(alt)) {
                                    wordOptions.add(alt);
                                }
                            }
                            if (!multipleGroups) break outer;
                        }
                    }
                }
                options.add(wordOptions);
                lidsPer.add(wordLids);
                vidsPer.add(wordVids);
            }
            generateCombinations(options, lidsPer, vidsPer, words, 0, new ArrayList<>(), new HashSet<>(), new HashSet<>(), result, toBeReplaced, new HashSet<>());
        }
        return result;
    }

    private static void generateCombinations(List<List<String>> options, List<Set<String>> lidsPer, List<Set<String>> vidsPer, String[] originalWords, int index, List<String> current, Set<String> currentLids, Set<String> currentVids, List<Map<String, Object>> result, String original, Set<String> seenVariants) {
        if (index == options.size()) {
            String variant = String.join(" ", current);
            if (!variant.equals(original) && seenVariants.add(variant)) {
                String lidsStr = currentLids.isEmpty() ? "NA" : String.join(",", currentLids);
                String vidsStr = currentVids.isEmpty() ? "NA" : String.join(",", currentVids);
                Map<String, Object> info = new HashMap<>();
                info.put("variant", variant);
                info.put("lookupIds", lidsStr);
                info.put("lookupValueIds", vidsStr);
                result.add(info);
            }
            return;
        }

        for (String choice : options.get(index)) {
            current.add(choice);
            Set<String> newLids = new HashSet<>(currentLids);
            Set<String> newVids = new HashSet<>(currentVids);
            if (!choice.equals(originalWords[index])) {
                newLids.addAll(lidsPer.get(index));
                newVids.addAll(vidsPer.get(index));
            }
            generateCombinations(options, lidsPer, vidsPer, originalWords, index + 1, current, newLids, newVids, result, original, seenVariants);
            current.remove(current.size() - 1);
        }
    }

    public static List<String> generateStopwordVariants(String originalValue, String stop) {
        List<String> variants = new ArrayList<>();
        String[] words = originalValue.split("\\s+");
        int numWords = words.length;

        // Prefix
        variants.add(stop + " " + originalValue);

        // Suffix
        variants.add(originalValue + " " + stop);

        // Between
        if (numWords > 1) {
            for (int pos = 1; pos < numWords; pos++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < pos; j++) {
                    sb.append(words[j]).append(" ");
                }
                sb.append(stop).append(" ");
                for (int j = pos; j < numWords; j++) {
                    sb.append(words[j]).append(" ");
                }
                variants.add(sb.toString().trim());
            }
        }
        return variants;
    }

    public static List<String> generate2CedVariants(String input) {
        List<String> variants = new ArrayList<>();
        int len = input.length();

        // Delete 2 characters
        if (len >= 3) {
            variants.add(input.substring(2)); // remove first two
            variants.add(input.substring(0, len / 2 - 1) + input.substring((len / 2) + 1)); // remove around middle
            variants.add(input.substring(0, len - 2)); // remove last two
        }

//        // Insert 2 characters
//        variants.add(INSERT_CHAR + INSERT_CHAR + input); // insert two at start
//        variants.add(input.substring(0, len / 2) + INSERT_CHAR + INSERT_CHAR + input.substring(len / 2)); // middle
//        variants.add(input + INSERT_CHAR + INSERT_CHAR); // insert two at end

        return variants;
    }

    public static List<String> generate3CedVariants(String input) {
        List<String> variants = new ArrayList<>();
        int len = input.length();

        // Delete 3 characters
        if (len >= 4) {
            variants.add(input.substring(3)); // remove first 3
            variants.add(input.substring(0, len / 2 - 1) + input.substring((len / 2) + 2)); // remove around middle
            variants.add(input.substring(0, len - 3)); // remove last 3
        }

//        // Insert 3 characters
//        variants.add("" + INSERT_CHAR + INSERT_CHAR + INSERT_CHAR + input); // insert 3 at start
//        variants.add(input.substring(0, len / 2) + INSERT_CHAR + INSERT_CHAR + INSERT_CHAR + input.substring(len / 2)); // middle
//        variants.add(input + INSERT_CHAR + INSERT_CHAR + INSERT_CHAR); // end

        return variants;
    }

    private static int getMaxIndex(Properties props, String prefix) throws Exception {
        int maxIndex = 0;
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefix + "[")) {
                try {
                    int index = Integer.parseInt(key.substring(prefix.length() + 1, key.length() - 1));
                    maxIndex = Math.max(maxIndex, index);
                } catch (NumberFormatException e) {
                    throw new Exception("Something went wrong while getting maxIndex",e);
                }
            }
        }
        return maxIndex;
    }

    public static String loadJsonFromFile(String filePath) {
        try {
            File file = new File(filePath);

            // Check if the file exists
            if (!file.exists()) {
                System.out.println("File not found: " + filePath);
                return null;
            }

            // Read the entire file content
            String jsonContent = Files.readString(Path.of(file.getPath()));
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
            SourceInputModel sourceInputModel = objectMapper.readValue(jsonContent, SourceInputModel.class);

            // Parse the JSON data using JSONObject
            JSONObject jsonObject = new JSONObject(sourceInputModel);

            // Convert the parsed JSON back to a string
            return jsonObject.toString(4);
        } catch (Exception e) {
            System.out.println("An error occurred while reading the file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
public static void writeJsonAsExcelFile(JSONArray jsonArray, String transactionService, String tagName, String webService, String watchlistType, BlockingQueue<File> queue) throws IOException, InterruptedException {
        // Create a subfolder "out" inside it
        if (!Constants.OUTPUT_FOLDER.exists()) {
            Constants.OUTPUT_FOLDER.mkdirs();  // Create the folder if it doesn't exist
        }

        // Load configuration for Excel splitting
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file for Excel splitting: " + e.getMessage());
            throw e;
        }

        int rowLimit;
        try {
            String rowLimitStr = props.getProperty(Constants.EXCEL_SPLIT_ROW_LIMIT, String.valueOf(Constants.DEFAULT_ROW_LIMIT));
            rowLimit = Integer.parseInt(rowLimitStr);
        } catch (NumberFormatException e) {
            System.err.println("Invalid row limit value, using default: " + Constants.DEFAULT_ROW_LIMIT);
            rowLimit = Constants.DEFAULT_ROW_LIMIT;
        }

if (jsonArray.length() <= rowLimit) {
            // Write to a single file if splitting is not enabled or data is within limit
String fileName = String.format(Constants.OUTPUT_FILE_NAME_PATTERN, 1) + Constants.XLSX_EXT;
File outputFile = new File(Constants.OUTPUT_FOLDER, fileName);
            writeSingleExcelFile(jsonArray, transactionService, tagName, webService, watchlistType, outputFile);
if (queue != null) {
    queue.put(outputFile);
}
if (queue != null) {
    queue.put(new File(Constants.POISON_PILL));
}
File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
try (FileWriter fw = new FileWriter(countFile)) {
    fw.write("1");
} catch (IOException e) {
    System.err.println("Error writing output file count to " + countFile.getAbsolutePath() + ": " + e.getMessage());
}
System.out.println("Successfully wrote to Excel (" + outputFile.getName() + ") file.");
System.out.println("Output file count (1) saved to: " + countFile.getAbsolutePath());
        } else {
            // Split data into multiple files
            int fileIndex = 1;
            int startIndex = 0;
            while (startIndex < jsonArray.length()) {
                int endIndex = Math.min(startIndex + rowLimit, jsonArray.length());
                JSONArray chunk = new JSONArray();
                for (int i = startIndex; i < endIndex; i++) {
                    chunk.put(jsonArray.getJSONObject(i));
                }
                String fileName = String.format(Constants.OUTPUT_FILE_NAME_PATTERN, fileIndex) + Constants.XLSX_EXT;
                File outputFile = new File(Constants.OUTPUT_FOLDER, fileName);
                writeSingleExcelFile(chunk, transactionService, tagName, webService, watchlistType, outputFile);
                if (queue != null) {
                    queue.put(outputFile);
                }
                fileIndex++;
                startIndex = endIndex;
            }
            // Store the count of output files created, adjusting for the last increment since fileIndex is incremented after the last file
            File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
            int totalFiles = fileIndex - 1; // Adjust for the last increment
            try (FileWriter fw = new FileWriter(countFile)) {
                fw.write(String.valueOf(totalFiles));
            } catch (IOException e) {
                System.err.println("Error writing output file count to " + countFile.getAbsolutePath() + ": " + e.getMessage());
            }
            System.out.println("Successfully wrote to multiple Excel files with prefix (" + Constants.OUTPUT_FILE_NAME + "_N.xlsx).");
            System.out.println("Output file count (" + totalFiles + ") saved to: " + countFile.getAbsolutePath());
            if (queue != null) {
                queue.put(new File(Constants.POISON_PILL));
            }
        }
    }

private static void writeSingleExcelFile(JSONArray jsonArray, String transactionService, String tagName, String webService, String watchlistType, File outputFile) throws IOException {
        // Create the Excel workbook and sheet
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Output");

        // Header row
        String thirdColumn = Constants.MESSAGE + transactionService.toUpperCase();
        String[] headers = {
                Constants.SEQ_NO,
                Constants.RULE,
                thirdColumn,
                Constants.TAG,
                Constants.SOURCE_INPUT,
                Constants.TARGET_INPUT,
                Constants.TARGET_COLUMN,
                Constants.WATCHLIST,
                Constants.NUID
        };

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        // Write JSON data
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            String rawMessage = jsonObject.toString(4)
                    .replace("\\r", "\r")
                    .replace("\\n", "\n")
                    .replace("~~~~", "\\\\")
                    .replace("<\\/", "</");

            JSONObject additionalData = jsonObject.getJSONObject(Constants.ADDITIONAL_DATA);
            String sourceInput = additionalData.getString(Constants.VALUE);
            String targetInput = additionalData.getString(Constants.ORIGINAL_VALUE);
            String targetColumn = additionalData.getString(Constants.COLUMN);
            String uid = additionalData.getString(Constants.UID);
            int ced = additionalData.getInt((Constants.CED));

            String type = "";
            if (ced == 0) type = Constants.EXACT;
            else if (ced > 0) type = Constants.FUZZY + ced + Constants.CED;
            else if (ced == -1) type = "STOPWORD";
            else if (ced == -2) type = "SYNONYM";

            String ruleName = webService+" "+type;

            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(i + 1);      // SeqNo
            row.createCell(1).setCellValue(ruleName);         // Rule Name
            row.createCell(2).setCellValue(rawMessage); // Message <SERVICE>
            row.createCell(3).setCellValue(tagName);         // Tag
            row.createCell(4).setCellValue(sourceInput);         // Source Input
            row.createCell(5).setCellValue(targetInput);         // Target Input
            row.createCell(6).setCellValue(targetColumn);         // Target Column
            row.createCell(7).setCellValue(watchlistType);         // Watchlist
            row.createCell(8).setCellValue(uid);         // N_UID
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Write to file
        try (FileOutputStream fileOut = new FileOutputStream(outputFile)) {
            workbook.write(fileOut);
        }
        workbook.close();
        System.out.println("Successfully wrote to Excel (" + outputFile.getName() + ") file.");
    }

public static void writeRawMessagesToJsonFile(JSONArray jsonArray) throws IOException {
        if (!Constants.OUTPUT_FOLDER.exists()) {
            Constants.OUTPUT_FOLDER.mkdirs();
        }

        // Load configuration for Excel splitting to match JSON splitting
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file for JSON splitting: " + e.getMessage());
            throw e;
        }

        int rowLimit = Constants.DEFAULT_ROW_LIMIT;
        try {
            String rowLimitStr = props.getProperty(Constants.EXCEL_SPLIT_ROW_LIMIT, String.valueOf(Constants.DEFAULT_ROW_LIMIT));
            rowLimit = Integer.parseInt(rowLimitStr);
        } catch (NumberFormatException e) {
            System.err.println("Invalid row limit value for JSON splitting, using default: " + Constants.DEFAULT_ROW_LIMIT);
            rowLimit = Constants.DEFAULT_ROW_LIMIT;
        }

if (jsonArray.length() <= rowLimit) {
            // Write to a single file if splitting is not enabled or data is within limit
String fileName = String.format(Constants.OUTPUT_FILE_NAME_PATTERN, 1) + Constants.JSON_EXT;
File outputFile = new File(Constants.OUTPUT_FOLDER, fileName);
try (FileOutputStream fos = new FileOutputStream(outputFile)) {
    fos.write(jsonArray.toString(4).getBytes(Constants.ENCODER));
}
File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
try (FileWriter fw = new FileWriter(countFile)) {
    fw.write("1");
} catch (IOException e) {
    System.err.println("Error writing output file count to " + countFile.getAbsolutePath() + ": " + e.getMessage());
}
System.out.println("Successfully wrote raw messages to JSON (" + outputFile.getName() + ") file.");
System.out.println("Output file count (1) saved to: " + countFile.getAbsolutePath());
        } else {
            // Split data into multiple files
            int fileIndex = 1;
            int startIndex = 0;
            while (startIndex < jsonArray.length()) {
                int endIndex = Math.min(startIndex + rowLimit, jsonArray.length());
                JSONArray chunk = new JSONArray();
                for (int i = startIndex; i < endIndex; i++) {
                    chunk.put(jsonArray.getJSONObject(i));
                }
                String fileName = String.format(Constants.OUTPUT_FILE_NAME_PATTERN, fileIndex) + Constants.JSON_EXT;
                File outputFile = new File(Constants.OUTPUT_FOLDER, fileName);
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(chunk.toString(4).getBytes(Constants.ENCODER));
                }
                System.out.println("Successfully wrote raw messages to JSON (" + fileName + ") file.");
                fileIndex++;
                startIndex = endIndex;
            }
            // Store the count of output files created, adjusting for the last increment since fileIndex is incremented after the last file
            File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
            int totalFiles = fileIndex - 1; // Adjust for the last increment
            try (FileWriter fw = new FileWriter(countFile)) {
                fw.write(String.valueOf(totalFiles));
            } catch (IOException e) {
                System.err.println("Error writing output file count to " + countFile.getAbsolutePath() + ": " + e.getMessage());
            }
            System.out.println("Successfully wrote to multiple Excel files with prefix (" + Constants.OUTPUT_FILE_NAME + "_N.xlsx).");
            System.out.println("Output file count (" + totalFiles + ") saved to: " + countFile.getAbsolutePath());
        }
    }
}
