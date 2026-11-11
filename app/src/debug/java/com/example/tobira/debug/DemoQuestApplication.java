package com.example.tobira.debug;

import android.app.Application;
import android.util.Log;

import com.example.tobira.data.QuestRepository;

/** Installs the draft fixture on debug startup without starting a playthrough. */
public final class DemoQuestApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        QuestRepository repository = QuestRepository.getInstance(this);
        repository.initialize()
                .thenCompose(ignored -> DemoQuestCatalog.install(repository))
                .thenCompose(ignored -> IzumoQuestCatalog.install(repository))
                .whenComplete((added, error) -> {
                    if (error != null) {
                        Log.e("DemoQuest", "Cannot install demo quest", error);
                    } else {
                        Log.i("DemoQuest", added ? "Demo quest installed" : "Demo quest already present");
                    }
                });
    }
}
