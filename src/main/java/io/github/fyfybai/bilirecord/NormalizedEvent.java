package io.github.fyfybai.bilirecord;

public record NormalizedEvent(
        EventKind kind,
        Long uid,
        String username,
        String content,
        String itemName,
        Integer quantity,
        Long price,
        String priceUnit,
        Integer guardLevel,
        GuardPurchaseKind purchaseKind,
        String title,
        String area) {

    public String summary() {
        return switch (kind) {
            case LIVE -> "直播开始";
            case PREPARING -> "直播结束";
            case DANMAKU -> displayName() + "：" + display(content, "弹幕");
            case ROOM_CHANGE -> "直播间更新：" + display(title, "信息已更新")
                    + (area == null || area.isBlank() ? "" : " · " + area);
            case GIFT -> displayName() + " 赠送 " + display(itemName, "礼物") + quantitySuffix();
            case SUPER_CHAT -> displayName() + "：" + display(content, "醒目留言")
                    + (price == null ? "" : " · ¥" + price);
            case GUARD -> displayName() + " " + guardAction() + " " + guardName()
                    + quantitySuffix();
        };
    }

    private String displayName() {
        return username == null || username.isBlank() ? "用户" : username;
    }

    private static String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String quantitySuffix() {
        return quantity == null || quantity <= 1 ? "" : " × " + quantity;
    }

    private String guardAction() {
        return switch (purchaseKind == null ? GuardPurchaseKind.UNKNOWN : purchaseKind) {
            case NEW -> "开通了";
            case RENEW -> "续费了";
            case UNKNOWN -> "购买了";
        };
    }

    private String guardName() {
        if (itemName != null && !itemName.isBlank()) {
            return itemName;
        }
        return switch (guardLevel == null ? 0 : guardLevel) {
            case 1 -> "总督";
            case 2 -> "提督";
            case 3 -> "舰长";
            default -> "大航海";
        };
    }
}
