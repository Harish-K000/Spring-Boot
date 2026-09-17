import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
class EvalSqlQuery {
    ResultSet select(Statement db, String accountId) throws SQLException {
        return db.executeQuery("SELECT * FROM orders WHERE account_id = " + accountId);
    }
}
