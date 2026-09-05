package io.stamethyst.compatmod.save;

final class EventRollPriorRoomState {
    private Boolean pendingReplayPriorRoomWasShop;
    private Boolean lastEventRollPriorRoomWasShop;

    void load(Boolean value) {
        pendingReplayPriorRoomWasShop = value;
    }

    boolean prepareRoll(boolean loadingSave, boolean currentRoomWasShop) {
        if (!loadingSave) {
            pendingReplayPriorRoomWasShop = null;
            lastEventRollPriorRoomWasShop = currentRoomWasShop;
            return false;
        }

        Boolean value = pendingReplayPriorRoomWasShop;
        pendingReplayPriorRoomWasShop = null;
        lastEventRollPriorRoomWasShop = value;
        return Boolean.TRUE.equals(value);
    }

    Boolean valueForSave(boolean postCombatQuestionRoom, boolean currentRoomWasShop) {
        if (postCombatQuestionRoom && lastEventRollPriorRoomWasShop != null) {
            return lastEventRollPriorRoomWasShop;
        }
        return currentRoomWasShop;
    }
}
