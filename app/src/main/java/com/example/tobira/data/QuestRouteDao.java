package com.example.tobira.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;
import com.example.tobira.data.QuestRouteEntities.*;

@Dao
public interface QuestRouteDao {
    @Query("SELECT * FROM quest_route_members WHERE questId = :id AND questVersion = :version AND finale = 1")
    List<Member> getFinaleMemberships(String id, int version);

    @Insert void addRoute(Route route);
    @Insert void addMembers(List<Member> members);

    @Query("SELECT * FROM quest_routes WHERE id = :id AND version = :version")
    Route getRoute(String id, int version);

    @Query("SELECT * FROM quest_routes ORDER BY title, version DESC")
    LiveData<List<Route>> observeRoutes();

    @Query("SELECT * FROM quest_route_members WHERE routeId = :id AND routeVersion = :version ORDER BY position")
    List<Member> getMembers(String id, int version);
}
