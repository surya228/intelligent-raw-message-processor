package com.oracle.ofss.sanctions.tf.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Properties;

import javax.net.ssl.HttpsURLConnection;

public class ToggleMatchingEngine {
    private static final Logger logger = LoggerFactory.getLogger(ToggleMatchingEngine.class);

    int currentNId=-1;

    public String toggleMatchingEngine() throws Exception {
        String newEsOs = toggle();
        refreshCache("/tfcs-matching-service/refreshCacheSearchengine");
        refreshCache("/tfcs-matching-service/refreshCache");
        Thread.sleep(Constants.THREAD_SLEEP_MS); // wait for some time to refresh cache fully
        return newEsOs;
    }

    private static void refreshCache(String endPoint) throws Exception {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            logger.error("Error reading properties file: " + e.getMessage());
            throw e;
        }

        String tokenUrl = props.getProperty(Constants.TOKEN_URL);
        String clientId = props.getProperty(Constants.CLIENT_ID);
        String clientSecret = props.getProperty(Constants.CLIENT_SECRET);
        String devcorp7 = props.getProperty(Constants.DEVCORP7);
        String namespace = props.getProperty(Constants.NAMESPACE);
        String retryRequiredFlag = props.getProperty(Constants.RETRY_REQUIRED_FLAG, Constants.YES);
        int retryMaxCount = Integer.parseInt(props.getProperty(Constants.RETRY_MAX_COUNT, String.valueOf(Constants.DEFAULT_RETRY_MAX)));

        String bearerToken = MessageProcessingUtility.getAccessToken(tokenUrl, clientId, clientSecret);

        String apiUrl = devcorp7 + "/" + namespace + endPoint;

        int retryCount = 0;
        int responseCode;
        StringBuilder apiResponse = new StringBuilder();
        BufferedReader br = null;

        do {
            if (retryCount > 0) {
                try {
                    Thread.sleep(Constants.THREAD_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            URL url = new URL(apiUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("ofs_remote_user", "OFS_SRV_ACCT");
            conn.setRequestProperty("accept-language", "en-US,en-U");
            conn.setRequestProperty("authorization", Constants.AUTH_BEARER_PREFIX + bearerToken);
            conn.setRequestProperty("idcs_remote_user", "appuser");
            conn.setRequestProperty("locale", "en-US");
            conn.setHostnameVerifier((hostname, sslSession) -> true);

            responseCode = conn.getResponseCode();

            if (responseCode != Constants.NO_CONTENT) {
                br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                String output;
                while ((output = br.readLine()) != null) {
                    apiResponse.append(output);
                }
                br.close();
            }
            conn.disconnect();

            retryCount++;
        } while (Constants.YES.equalsIgnoreCase(retryRequiredFlag) && responseCode != Constants.NO_CONTENT && retryCount <= retryMaxCount);

        if (responseCode != Constants.NO_CONTENT) {
            throw new Exception("Failed to refresh cache after " + retryMaxCount + " retries. Response: " + responseCode + " - " + apiResponse.toString());
        }

        logger.info("Cache refreshed successfully.");
    }

    private String toggle() throws Exception {
        Connection conn = null;
        try {
            conn = SQLUtility.getDbConnection();
            conn.setAutoCommit(false);

            String currentEsOs = findCurrentMatchingEngine();

            // Update the active row to 'N'
            String updateSql = "UPDATE fcc_mr_c_matchingtarget SET F_LRI_FLAG = 'N', V_ACTION_BY = 'utility', D_ACTION = ? WHERE N_ID = ?";
            try (PreparedStatement pstmtUpdate = conn.prepareStatement(updateSql)) {
                pstmtUpdate.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
                pstmtUpdate.setInt(2, this.currentNId);
                pstmtUpdate.executeUpdate();
            }

            // Determine the new values
            String newEsOs = "OS".equals(currentEsOs) ? "OT" : "OS";
            String newMatchingEngine = "OS".equals(newEsOs) ? "Open Search" : "Oracle Text";

            // Get max N_ID
            String queryMaxId = "SELECT MAX(N_ID) AS MAX_ID FROM fcc_mr_c_matchingtarget";
            int newNId;
            try (PreparedStatement pstmtMax = conn.prepareStatement(queryMaxId);
                 ResultSet rsMax = pstmtMax.executeQuery()) {
                rsMax.next();
                newNId = rsMax.getInt("MAX_ID") + 1;
            }

            // Insert the new row with 'Y'
            String insertSql = "INSERT INTO fcc_mr_c_matchingtarget (N_ID, V_ACTION_BY, D_ACTION, F_LRI_FLAG, F_ES_OS, V_MATCHING_ENGINE) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmtInsert = conn.prepareStatement(insertSql)) {
                pstmtInsert.setInt(1, newNId);
                pstmtInsert.setString(2, "appuser");
                pstmtInsert.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                pstmtInsert.setString(4, "Y");
                pstmtInsert.setString(5, newEsOs);
                pstmtInsert.setString(6, newMatchingEngine);
                pstmtInsert.executeUpdate();
            }

            conn.commit();
            return newEsOs;
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    // Ignore rollback error
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Ignore close error
                }
            }
        }
    }

    public String findCurrentMatchingEngine() throws Exception{
        Connection conn = null;
        try {
            conn = SQLUtility.getDbConnection();
            conn.setAutoCommit(false);

            // Query the current active row (F_LRI_FLAG = 'Y')
            String queryActive = "SELECT N_ID, F_ES_OS FROM fcc_mr_c_matchingtarget WHERE F_LRI_FLAG = 'Y'";
            try (PreparedStatement pstmtQuery = conn.prepareStatement(queryActive);
                 ResultSet rs = pstmtQuery.executeQuery()) {

                if (!rs.next()) {
                    throw new SQLException("No active matching engine found (no row with F_LRI_FLAG = 'Y')");
                }

                this.currentNId = rs.getInt("N_ID");
                return rs.getString("F_ES_OS");
            }
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    // Ignore rollback error
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Ignore close error
                }
            }
        }
    }
}
