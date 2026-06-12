/*
 * This file is part of aion-lightning <aion-lightning.org>.
 * (License info retained)
 */
package mysql5;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.loginserver.dao.TaskFromDBDAO;
import com.aionemu.loginserver.taskmanager.handler.TaskFromDBHandler;
import com.aionemu.loginserver.taskmanager.handler.TaskFromDBHandlerHolder;
import com.aionemu.loginserver.taskmanager.trigger.TaskFromDBTrigger;
import com.aionemu.loginserver.taskmanager.trigger.TaskFromDBTriggerHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.ArrayList;

public class MySQL5TaskFromDBDAO extends TaskFromDBDAO {

	private static final Logger log = LoggerFactory.getLogger(MySQL5TaskFromDBDAO.class);
	private static final String SELECT_ALL_QUERY = "SELECT * FROM tasks ORDER BY id";

	@Override
	public ArrayList<TaskFromDBTrigger> getAllTasks() {
		final ArrayList<TaskFromDBTrigger> result = new ArrayList<TaskFromDBTrigger>();

		Connection con = null;
		PreparedStatement stmt = null;
		try {
			con = DatabaseFactory.getConnection();
			stmt = con.prepareStatement(SELECT_ALL_QUERY);

			ResultSet rset = stmt.executeQuery();

			while (rset.next()) {
				try {
					// PERBAIKAN: Java 9+ getDeclaredConstructor().newInstance()
					TaskFromDBTrigger trigger = TaskFromDBTriggerHolder.valueOf(rset.getString("trigger_type")).getTriggerClass().getDeclaredConstructor().newInstance();
					TaskFromDBHandler handler = TaskFromDBHandlerHolder.valueOf(rset.getString("task_type")).getTaskClass().getDeclaredConstructor().newInstance();

					handler.setTaskId(rset.getInt("id"));

					String execParamsResult = rset.getString("exec_param");
					if (execParamsResult != null) {
						handler.setParams(rset.getString("exec_param").split(" "));
					}

					trigger.setHandlerToTrigger(handler);

					String triggerParamsResult = rset.getString("trigger_param");
					if (triggerParamsResult != null) {
						trigger.setParams(rset.getString("trigger_param").split(" "));
					}

					result.add(trigger);

				} catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException ex) {
					log.error(ex.getMessage(), ex);
				}
			}

			rset.close();
			stmt.close();
		} catch (SQLException e) {
			log.error("Loading tasks failed: ", e);
		} finally {
			DatabaseFactory.close(stmt, con);
		}

		return result;
	}

	@Override
	public boolean supports(String s, int i, int i1) {
		return MySQL5DAOUtils.supports(s, i, i1);
	}
}