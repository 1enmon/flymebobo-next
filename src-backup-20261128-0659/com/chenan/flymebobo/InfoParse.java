package com.chenan.flymebobo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v601/v602: 信息卡片本地解析（从独立测试 App「信
 * 息卡片」移植，wei 定：内置进模块、只在轻气泡面
 * 板显示）。本地为主、AI 辅助：火车票/机票/快递/
 * 账单这类规整格式全走本地正则，即时、离线、免
 * 费；认不出返回 null（照常当普通收纳行）。
 * v602 规则加固：日期多格式（年月日/ISO/月日号）、
 * 时间多格式（HH:mm/H时mm分）、车次兜底（车次：
 * G1234）、站名去黏连（从/由/到达…）与假站名过
 * 滤、座位「车厢」写法、检票口/登机口冒号、航班
 * 时间优先取「起飞」附近、航线箭头缺失时用机场名
 * 兜底、快递补极兔 JT 与数字边界守卫、账单补
 * 「xx.xx元」金额与 M/D 到期日。
 */
public class InfoParse {

    public static class Card {
        public String id;          // 去重主键
        public String kind;        // train/flight/courier/bill
        public String title;
        public String sub;
        public String extra;
        public String source;      // 来源包名
        public String copyText;    // 快递单号，可空
        public boolean ai;         // v605: AI 兜底/核对产出的卡
        public String fp;          // v606: 票据指纹——判「同一张票」用（订单号优先）
        public long eventTime;     // 发车/起飞/到期，0=无
        public long createdAt;
    }

    private static String find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** v602: 地名清洗——正则贪心会把「分从重庆北」
     * 「至深圳北」「往北京首都」这类黏连前缀一起抓
     * 进来。逐字剥离介词/时间残字，但剥完必须剩
     * >=2 字（防「从化站」被剥成「化」）。 */
    private static String cleanPlace(String p) {
        if (p == null) {
            return null;
        }
        String stops = "从由经在至往飞赴达前分时点";
        while (p.length() > 2
                && stops.indexOf(p.charAt(0)) >= 0) {
            p = p.substring(1);
        }
        return p;
    }

    // ---------- 日期时间 ----------

    /** 在 s 里找日期，返回 [年(0=缺省), 月, 日]，找不到 null。
     * 支持 yyyy年M月d日 / M月d日 / M月d号 / yyyy-MM-dd / yyyy/M/d。 */
    private static int[] findDate(String s) {
        Matcher m = Pattern.compile(
                "(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]")
                .matcher(s);
        if (m.find()) {
            return new int[]{
                    m.group(1) == null ? 0
                            : Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))};
        }
        m = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})")
                .matcher(s);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))};
        }
        return null;
    }

    private static String dateLabel(int[] d) {
        if (d == null) {
            return null;
        }
        return (d[0] > 0 ? d[0] + "年" : "")
                + d[1] + "月" + d[2] + "日";
    }

    /** 找时间：先在关键词附近找（起飞/出发/开…），再全局。
     * 返回 [时, 分]，找不到 null。支持 HH:mm 与 H时mm分。 */
    private static int[] findTime(String s, String... nearWords) {
        for (String w : nearWords) {
            if (w == null || w.isEmpty()) {
                continue;
            }
            Matcher m = Pattern.compile(Pattern.quote(w)
                    + "[^0-9]{0,8}?(\\d{1,2})[:：时](\\d{2})")
                    .matcher(s);
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2))};
            }
            m = Pattern.compile("(\\d{1,2})[:：时](\\d{2})"
                    + "\\s*(?:分)?\\s*" + Pattern.quote(w))
                    .matcher(s);
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2))};
            }
        }
        Matcher m = Pattern.compile("(\\d{1,2})[:：](\\d{2})")
                .matcher(s);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2))};
        }
        m = Pattern.compile("(\\d{1,2})时(\\d{2})分").matcher(s);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2))};
        }
        return null;
    }

    private static String timeLabel(int[] t) {
        if (t == null) {
            return null;
        }
        return (t[0] < 10 ? "0" : "") + t[0] + ":"
                + (t[1] < 10 ? "0" : "") + t[1];
    }

    /** 拼时间戳。日期缺省：有时间时按今天算（已过 2
     * 小时以上则顺延明天——多为次日行程的提前通知）。 */
    private static long buildTime(int[] date, int[] time) {
        try {
            Calendar now = Calendar.getInstance();
            Calendar c = Calendar.getInstance();
            if (date == null) {
                if (time == null) {
                    return 0;
                }
                c.set(Calendar.HOUR_OF_DAY, time[0]);
                c.set(Calendar.MINUTE, time[1]);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis()
                        < now.getTimeInMillis()
                                - 2L * 3600 * 1000) {
                    c.add(Calendar.DAY_OF_YEAR, 1);
                }
                return c.getTimeInMillis();
            }
            int year = date[0] > 0 ? date[0]
                    : now.get(Calendar.YEAR);
            c.set(year, date[1] - 1, date[2],
                    time == null ? 0 : time[0],
                    time == null ? 0 : time[1], 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------- 入口 ----------

    public static Card tryParse(String pkg, String title,
            String text, long when) {
        String all = ((title == null) ? "" : title) + " "
                + ((text == null) ? "" : text);
        if (all.trim().length() < 6) {
            return null;
        }
        Card c = tryTrain(all, pkg, when);
        if (c != null) {
            return c;
        }
        c = tryFlight(all, pkg, when);
        if (c != null) {
            return c;
        }
        c = tryCourier(all, pkg, when);
        if (c != null) {
            return c;
        }
        return tryBill(all, pkg, when);
    }

    private static Card base(String kind, String pkg,
            long when) {
        Card c = new Card();
        c.kind = kind;
        c.source = pkg;
        c.createdAt = when;
        return c;
    }

    // ---------- 火车票 ----------

    private static Card tryTrain(String s, String pkg,
            long when) {
        String trainNo = find(s, "([GDCZKTS]\\d{1,4})\\s*次");
        if (trainNo == null) {
            trainNo = find(s, "车次[:：]?\\s*([GDCZKTS]\\d{1,4})");
        }
        if (trainNo == null) {
            // 纯数字车次（如 1461 次）要有铁路语境才认
            if (s.contains("铁路") || s.contains("12306")
                    || s.contains("列车")) {
                trainNo = find(s, "(\\d{3,4})\\s*次列车");
            }
        }
        if (trainNo == null) {
            return null;
        }
        trainNo = trainNo.replace(" ", "");
        // 站名：收集所有「X站」，去黏连前缀、滤掉泛称
        List<String> stations = new ArrayList<>();
        Matcher sm = Pattern.compile(
                "([\\u4e00-\\u9fa5]{2,7}?)站").matcher(s);
        while (sm.find() && stations.size() < 4) {
            String st = cleanPlace(sm.group(1).replaceAll(
                    "^(到达|抵达|开往|前往)", ""));
            if (st == null || st.length() < 2
                    || "出发".equals(st) || "到达".equals(st)
                    || "终点".equals(st) || "始发".equals(st)
                    || "车".equals(st) || "本站".equals(st)
                    || "上车".equals(st)) {
                continue;
            }
            if (!stations.contains(st)) {
                stations.add(st);
            }
        }
        int[] date = findDate(s);
        int[] time = findTime(s, "开", "出发", "发车");
        if (date == null && time == null) {
            return null;
        }
        String seat = null;
        Matcher seatM = Pattern.compile(
                "(\\d{1,2})\\s*车(?:厢)?\\s*(\\d{1,3}[A-Fa-f]?)\\s*号")
                .matcher(s);
        if (seatM.find()) {
            seat = seatM.group(1) + "车" + seatM.group(2) + "号";
        }
        String seatClass = find(s,
                "(优选一等座|商务座|特等座|一等座|二等座|一等卧|二等卧|高级软卧|软卧|硬卧|硬座|动卧|无座)");
        String gate = find(s, "检票口[:：]?\\s*([0-9A-Za-z]+)");
        String order = find(s,
                "订单(?:号|编号)[:：]?\\s*([A-Z0-9]{8,})");
        if (order == null) {
            order = find(s, "电子客票号[:：]?\\s*([A-Z0-9]{8,})");
        }
        Card c = base("train", pkg, when);
        String from = stations.size() > 0 ? stations.get(0) : "";
        String to = stations.size() > 1 ? stations.get(1) : "";
        c.title = trainNo + " 次"
                + (from.isEmpty() ? ""
                        : " · " + from + " → " + to);
        StringBuilder sub = new StringBuilder();
        if (date != null) {
            sub.append(dateLabel(date)).append(" ");
        }
        if (time != null) {
            sub.append(timeLabel(time)).append(" 发车");
        }
        if (seat != null) {
            sub.append(" · ").append(seat);
        }
        if (seatClass != null) {
            sub.append(" · ").append(seatClass);
        }
        c.sub = sub.toString();
        StringBuilder ex = new StringBuilder();
        if (gate != null) {
            ex.append("检票口 ").append(gate);
        }
        if (order != null) {
            if (ex.length() > 0) {
                ex.append(" · ");
            }
            ex.append("订单号 ").append(order);
        }
        c.extra = ex.toString();
        c.eventTime = buildTime(date, time);
        c.id = "train:" + (order != null ? order
                : trainNo + "|" + dateLabel(date) + "|"
                        + timeLabel(time));
        // v606: 同票指纹——有订单号就认订单（一张订
        // 单的购票/改签/提醒短信都是同一张票）；没订
        // 单号退到 车次+日期+座位（同车次不同座位 =
        // 不同票）；再没座位用 车次+日期+区间 兜底。
        c.fp = order != null
                ? "train|ord:" + order
                : "train|" + trainNo + "|" + dateLabel(date)
                        + "|" + (seat != null ? seat
                                : from + ">" + to);
        return c;
    }

    // ---------- 机票 ----------

    private static Card tryFlight(String s, String pkg,
            long when) {
        if (!(s.contains("航班") || s.contains("起飞")
                || s.contains("登机") || s.contains("机场")
                || s.contains("航站楼"))) {
            return null;
        }
        // v602: 不能用 \b——Java 的 \b 按字母/数字判界，
        // 中文算字母，「的JD5712航」两边都不成界（实测
        // find=false）。用 ASCII 负向环视守两端；航司
        // 代码可数字开头（9C 春秋/3U 川航/8L 祥鹏），
        // 逐个候选验「前两字符含字母」，把日期数字串
        // （202611…）跳过去。
        String flightNo = null;
        Matcher fm = Pattern.compile(
                "(?<![A-Za-z0-9])([A-Z0-9]{2}\\d{3,4})(?![0-9])")
                .matcher(s);
        while (fm.find()) {
            String cand = fm.group(1);
            if (Character.isLetter(cand.charAt(0))
                    || Character.isLetter(cand.charAt(1))) {
                flightNo = cand;
                break;
            }
        }
        if (flightNo == null) {
            return null;
        }
        String route = null;
        Matcher rm = Pattern.compile(
                "([\\u4e00-\\u9fa5]{2,5})\\s*(?:→|->|—|至|飞往)"
                        + "\\s*([\\u4e00-\\u9fa5]{2,5})")
                .matcher(s);
        if (rm.find()) {
            route = cleanPlace(rm.group(1)) + " → "
                    + cleanPlace(rm.group(2));
        } else {
            // 箭头被括号/机场后缀隔断时，用「X机场」兜底
            List<String> airports = new ArrayList<>();
            Matcher am = Pattern.compile(
                    "([\\u4e00-\\u9fa5]{2,5}?)机场").matcher(s);
            while (am.find() && airports.size() < 2) {
                String ap = cleanPlace(am.group(1).replaceAll(
                        "^(到达|抵达|飞往|前往)", ""));
                if (ap != null && ap.length() >= 2
                        && !airports.contains(ap)) {
                    airports.add(ap);
                }
            }
            if (airports.size() >= 2) {
                route = airports.get(0) + " → " + airports.get(1);
            }
        }
        int[] date = findDate(s);
        int[] time = findTime(s, "起飞", "出发");
        String gate = find(s, "登机口[:：]?\\s*([0-9A-Za-z]+)");
        String seat = find(s, "座位(?:号)?[:：]?\\s*(\\d{1,3}[A-Z])");
        String cabin = find(s, "(头等舱|公务舱|商务舱|经济舱)");
        Card c = base("flight", pkg, when);
        c.title = flightNo
                + (route != null ? " · " + route : " 航班");
        StringBuilder sub = new StringBuilder();
        if (date != null) {
            sub.append(dateLabel(date)).append(" ");
        }
        if (time != null) {
            sub.append(timeLabel(time)).append(" 起飞");
        }
        if (gate != null) {
            sub.append(" · 登机口 ").append(gate);
        }
        if (seat != null) {
            sub.append(" · 座位 ").append(seat);
        }
        if (cabin != null) {
            sub.append(" · ").append(cabin);
        }
        c.sub = sub.toString();
        c.extra = "";
        c.eventTime = buildTime(date, time);
        c.id = "flight:" + flightNo + "|" + dateLabel(date)
                + "|" + timeLabel(time);
        // v606: 同票指纹——航班+日期+座位（同航班不
        // 同座位 = 不同票）；没座位退到起飞时间。
        c.fp = "flight|" + flightNo + "|" + dateLabel(date)
                + "|" + (seat != null ? seat : timeLabel(time));
        return c;
    }

    // ---------- 快递 ----------

    private static Card tryCourier(String s, String pkg,
            long when) {
        if (!(s.contains("快递") || s.contains("物流")
                || s.contains("快件") || s.contains("运单")
                || s.contains("单号") || s.contains("驿站")
                || s.contains("派送") || s.contains("签收"))) {
            return null;
        }
        // 带字母前缀的先认（公司可定）；纯数字单号两端
        // 加数字边界守卫，防截进更长的数字串里。
        String no = find(s,
                "(SF\\d{12,15}|YT\\d{10,13}|YTO\\d{10,13}"
                        + "|ZTO\\d{10,14}|JT\\d{10,13}"
                        + "|JD[A-Z0-9]{9,15}|EMS\\d{9,13})");
        if (no == null) {
            no = find(s, "(?<!\\d)(\\d{12,14})(?!\\d)");
        }
        if (no == null) {
            return null;
        }
        String company = "快递";
        if (no.startsWith("SF")) {
            company = "顺丰速运";
        } else if (no.startsWith("YT")) {
            company = "圆通速递";
        } else if (no.startsWith("ZTO") || no.startsWith("YTO")) {
            company = "中通快递";
        } else if (no.startsWith("JT")) {
            company = "极兔速递";
        } else if (no.startsWith("JD")) {
            company = "京东物流";
        } else if (no.startsWith("EMS")) {
            company = "邮政 EMS";
        } else if (s.contains("顺丰")) {
            company = "顺丰速运";
        } else if (s.contains("圆通")) {
            company = "圆通速递";
        } else if (s.contains("中通")) {
            company = "中通快递";
        } else if (s.contains("韵达")) {
            company = "韵达快递";
        } else if (s.contains("申通")) {
            company = "申通快递";
        } else if (s.contains("极兔")) {
            company = "极兔速递";
        } else if (s.contains("京东")) {
            company = "京东物流";
        } else if (s.contains("邮政")) {
            company = "邮政快递";
        }
        Card c = base("courier", pkg, when);
        c.title = company + " · 单号 " + no;
        c.sub = s.length() > 60 ? s.substring(0, 60) + "…" : s;
        c.extra = "";
        c.copyText = no;
        c.id = "courier:" + no;
        c.fp = c.id;
        return c;
    }

    // ---------- 账单 ----------

    private static Card tryBill(String s, String pkg,
            long when) {
        if (!(s.contains("信用卡") || s.contains("账单")
                || s.contains("应还款") || s.contains("最低还款"))) {
            return null;
        }
        String amount = find(s, "([¥￥]\\s*[\\d,]+\\.?\\d*)");
        if (amount == null) {
            amount = find(s, "([\\d,]+\\.\\d{2})\\s*元");
            if (amount != null) {
                amount = "¥" + amount;
            }
        }
        if (amount == null) {
            return null;
        }
        // 到期日：年月日式优先，其次在到期/还款日附近
        // 找 M月d日 或 M/D。
        int[] due = null;
        Matcher dm = Pattern.compile(
                "(?:到期|还款日|最后还款)[^，。]{0,8}?"
                        + "(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]")
                .matcher(s);
        if (dm.find()) {
            due = new int[]{dm.group(1) == null ? 0
                    : Integer.parseInt(dm.group(1)),
                    Integer.parseInt(dm.group(2)),
                    Integer.parseInt(dm.group(3))};
        } else {
            Matcher mm = Pattern.compile(
                    "(?:到期|还款日|最后还款)[^，。]{0,8}?"
                            + "(\\d{1,2})/(\\d{1,2})")
                    .matcher(s);
            if (mm.find()) {
                due = new int[]{0,
                        Integer.parseInt(mm.group(1)),
                        Integer.parseInt(mm.group(2))};
            }
        }
        String bank = find(s, "([\\u4e00-\\u9fa5]{2,6}银行)");
        Card c = base("bill", pkg, when);
        c.title = "信用卡账单 " + amount.replace(" ", "");
        StringBuilder sub = new StringBuilder();
        if (bank != null) {
            sub.append(bank);
        }
        if (due != null) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(dateLabel(due)).append(" 到期");
        }
        c.sub = sub.toString();
        c.extra = "";
        c.eventTime = due != null ? buildTime(due, null) : 0;
        c.id = "bill:" + pkg + "|" + amount + "|"
                + dateLabel(due);
        c.fp = c.id;
        return c;
    }
}
