import java.util.*;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Socket;

public class Bot2
{
    static final String TEAMNAME = "GOLDMANHACKS";
    static final String BUY = "BUY";
    static final String SELL = "SELL";
    static final String[] ALL_STOCKS = {"BOND", "VALBZ", "VALE", "GS", "MS", "WFC", "XLF"};

    static final int TRADE_UNIT = 15;

    static boolean rekt = false;
    static Book theBook = new Book();
    static ArrayList<Trade> pastTrades = new ArrayList<>();
    static ArrayList<Offer> myOffers = new ArrayList<>();
    static boolean areWeOpen = false;
    static long ourMoney = 0;
    static Map<String, Long> ourHoldings = new HashMap<>();
    static {
        for (String s : ALL_STOCKS)
            ourHoldings.put(s, 0L);
    }

    static long nextID = 1337;

    static ArrayList<String> toPrint;

    public static void main(String[] args)
    {
        long lastUpdate = System.currentTimeMillis();
        try
        {
            Socket skt;
            if (args[0].equals("live"))
                skt = new Socket("production", 20000);
            else if (args[0].equals("test"))
                skt = new Socket("test-exch-" + TEAMNAME, 20000);
            else
                return;
            BufferedReader from_exchange = new BufferedReader(new InputStreamReader(skt.getInputStream()));
            PrintWriter to_exchange = new PrintWriter(skt.getOutputStream(), true);

            to_exchange.println("HELLO " + TEAMNAME);
            long startTime = System.currentTimeMillis();
            main_loop:
            while (true)
            {
                if ("test".equals(args[0]) && System.currentTimeMillis() - startTime > 1000 * Integer.parseInt(args[1]))
                {
                    for (String sym : ALL_STOCKS)
                    {
                        PriceData dat = getFairPrice(sym);
                        System.err.print(sym + ": ");
                        if (dat == null)
                            System.err.println("NONE");
                        else
                            System.err.println(dat.mean + " " + dat.deviation);
                    }
                    break;
                }
                String raw_reply = from_exchange.readLine();
                if (raw_reply == null)
                    break;
                String[] reply = raw_reply.split("\\s+");
                // if (!"BOOK".equals(reply[0]))
                //     System.err.printf("The exchange replied: %s\n", raw_reply.trim());
                switch (reply[0]) {
                    case "OPEN":
                        areWeOpen = true;
                        break;
                    case "CLOSE":
                        areWeOpen = false;
                        break;
                    case "ERROR":
                        System.err.println("ERROR!");
                        break;
                    case "BOOK":
                        updateBook(reply);
                        break;
                    case "TRADE":
                        pastTrades.add(parseTrade(reply));
                        break;
                    case "ACK":
                        break;
                    case "REJECT":
                        continue main_loop;
                    case "FILL":
                        fillOurOffer(reply);
                        break;
                    case "OUT":
                        clearOfferWithID(Long.parseLong(reply[1]));
                        break;
                    default:
                        System.err.println("Unrecognized message type: " + reply[0]);
                }
                double est = estimatePL();
                if (System.currentTimeMillis() - lastUpdate > 1000)
                {
                    lastUpdate = System.currentTimeMillis();
                    System.err.println("Holdings");
                    for (String sym : ALL_STOCKS)
                        System.err.println(sym + " " + ourHoldings.get(sym));
                    System.err.println("Estimated P/L: " + est);
                }
                toPrint = new ArrayList<String>();
                if (est < -3000) {
                    while (!myOffers.isEmpty())
                        cancelOffer(myOffers.get(0));
                    rekt = true;
                }
                if (!rekt)
                {
                    makeSomeMoney();
                }
                for (String s : toPrint)
                {
                    // System.err.println("We sent: " + s);
                    // System.out.println("We sent: " + s);
                    to_exchange.println(s);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace(System.out);
        }
    }

    static double estimatePL()
    {
        double ans = ourMoney;
        for (String sym : ALL_STOCKS)
        {
            long x = ourHoldings.get(sym);
            PriceData dat = getFairPrice(sym);
            if (x == 0)
                continue;
            if (dat == null)
                return Double.NaN;
            ans += x * dat.mean;
        }
        return ans;
    }

    static void putBuyOrder(String sym, long price, long amount)
    {
        // if (amount <= 0)
        //     return;
        // long iden = nextID++;
        // myOffers.add(new Offer(
        //     iden,
        //     sym,
        //     BUY,
        //     price,
        //     amount));
        // toPrint.add("ADD " + iden + " " + sym + " " + BUY + " " + price + " " + amount);
    }

    static void putSellOrder(String sym, long price, long amount)
    {
        if (amount <= 0)
            return;
        long iden = nextID++;
        myOffers.add(new Offer(
            iden,
            sym,
            SELL,
            price,
            amount));
        toPrint.add("ADD " + iden + " " + sym + " " + SELL + " " + price + " " + amount);
    }

    static class PriceData
    {
        final double mean;
        final double deviation;
        PriceData(double a, double b) {
            mean = a;
            deviation = b;
        }
    }

    // returns -1 if not confident in estimate
    static PriceData getFairPrice(String sym)
    {
        if ("BOND".equals(sym))
            return new PriceData(1000, 0);
        if ("VALE".equals(sym))
            sym = "VALBZ";

        // SymbolEntry en = theBook.getEntry(sym);
        // if (en.buys.isEmpty() || en.sells.isEmpty())
        //     return null;
        // double mean = (double) (en.smallestBuy() + en.)

        final int CNT = 20;
        int sum = 0;
        int seen = 0;
        ArrayList<Long> values = new ArrayList<Long>();
        for (int i = pastTrades.size() - 1; i >= 0; i--)
        {
            Trade t = pastTrades.get(i);
            if (t.symbol.equals(sym))
            {
                sum += t.price;
                values.add(t.price);
                seen++;
                if (seen == CNT)
                    break;
            }
        }
        if (values.size() < CNT)
            return null;
        double mean = (double) sum / CNT;
        double variance = 0;
        for (long x : values)
            variance += (x - mean) * (x - mean);
        variance /= CNT - 1;
        return new PriceData(mean, Math.sqrt(variance));
    }

    static double getMeanFairPrice(String sym)
    {
        PriceData data = getFairPrice(sym);
        if (data == null)
            return -1;
        else
            return getFairPrice(sym).mean;
    }

    static double relativeError(double a, double b)
    {
        return Math.max(a, b) / Math.min(a, b) - 1;
    }

    static void cancelOffer(Offer o)
    {
        toPrint.add("CANCEL " + o.identifier);
        clearOfferWithID(o.identifier);
    }

    static int random(int a, int b)
    {
        return a + (int) (Math.random() * (b - a + 1));
    }

    static double randomMargin()
    {
        return 0;
        // return MIN_MARGIN + Math.random() * (MAX_MARGIN - MIN_MARGIN);
    }

    static void tradeAt(String sym, PriceData data, String buyOrSell)
    {
        double fairPrice = data.mean;
        int ourPrice;
        if (BUY.equals(buyOrSell))
            ourPrice = (int) Math.floor((1 - randomMargin()) * fairPrice) - 2;
        else
            ourPrice = (int) Math.ceil((1 + randomMargin()) * fairPrice) + 2;
        // ArrayList<Offer> ourBadOffers = new ArrayList<>();
        // for (Offer o : myOffers)
        // {
        //     if (o.symbol.equals(sym) && buyOrSell.equals(o.buyOrSell) && Math.abs(o.price - ourPrice) > 1)
        //         ourBadOffers.add(o);
        // }
        // for (Offer o : ourBadOffers)
        //     cancelOffer(o);
        // if (ourBadOffers.isEmpty())
        {
            if (BUY.equals(buyOrSell)) putBuyOrder(sym, ourPrice, Math.min(5, 50 - getMyBuying(sym)));
            else                      putSellOrder(sym, ourPrice, Math.min(5, 50 - getMySelling(sym)));
        }
    }

    static void convert (String sym, long amount, String buyOrSell)
    {
        if (amount <= 0)
            return;
        long iden = nextID++;
        toPrint.add("CONVERT" + " " + iden + " " + sym + " " + buyOrSell + " " + amount);
        System.err.println("CONVERT" + " " + iden + " " + sym + " " + buyOrSell + " " + amount);
    }

    // VALBZ is stable (more liquid)
    static void VALEVALALVLALALAVLVVAVAV()
    {
        PriceData dat = getFairPrice("VALBZ");
        if (dat == null)
            return;
        double price = dat.mean;
        SymbolEntry en = theBook.getEntry("VALE");
        long highestBuy = Integer.MIN_VALUE;
        for (Order o : en.buys)
            highestBuy = Math.max(highestBuy, o.price);
        long lowestSell = Integer.MAX_VALUE;
        for (Order o : en.sells)
            lowestSell = Math.min(lowestSell, o.price);
        if (lowestSell > price + 5 && getMySelling("VALE") <= 1)
            putSellOrder("VALE", lowestSell - 1, 1);
        if (highestBuy < price - 5 && getMyBuying("VALE") <= 1)
            putBuyOrder("VALE", highestBuy + 1, 1);
        long delta = ourHoldings.get("VALBZ") - getMyBuying("VALBZ") + getMySelling("VALBZ");
        putSellOrder("VALBZ", (long) Math.ceil(price), delta);
        putBuyOrder("VALBZ", (long) Math.floor(price), -delta);
        if (Math.abs(ourHoldings.get("VALE")) >= 9)
        {
            int mul = ourHoldings.get("VALE") > 0 ? 1 : -1;
            int am = 7;
            convert("VALE", am, mul == 1 ? "SELL" : "BUY");
            addHolding("VALE", -am * mul);
            addHolding("VALBZ", +am * mul);
        }
    }

    static void makeSomeMoney()
    {
        putBuyOrder("BOND", 999, 25 - getMyBuying("BOND"));
        putSellOrder("BOND", 1001, 25 - getMySelling("BOND"));
        VALEVALALVLALALAVLVVAVAV();
        for (String sym : ALL_STOCKS)
        {
            if ("VALE".equals(sym) || "VALBZ".equals(sym) || "BOND".equals(sym))
                continue;
            int margin = "VALE".equals(sym) ? 10 : 5;
            int tolerance = "VALE".equals(sym) ? 10000 : 2000;
            SymbolEntry en = theBook.getEntry(sym);
            if (en.frozen)
                continue;
            // if (getMyBuying(sym) == 0 && en.buys.size() >= 6)
            // {
            //     long mn = Integer.MAX_VALUE;
            //     for (Order o : en.buys)
            //         mn = Math.min(mn, o.price);
            //     putBuyOrder(sym, mn, TRADE_UNIT);
            // }
            // if (getMySelling(sym) == 0 && en.sells.size() >= 6)
            // {
            //     long mx = Integer.MIN_VALUE;
            //     for (Order o : en.sells)
            //         mx = Math.max(mx, o.price);
            //     putSellOrder(sym, mx, TRADE_UNIT);
            // }
            ArrayList<Trade> relevant = new ArrayList<Trade>();
            for (int i = pastTrades.size() - 1; i >= 0; i--)
            {
                Trade t = pastTrades.get(i);
                if (System.currentTimeMillis() - t.time > tolerance)
                    break;
                if (sym.equals(t.symbol))
                    relevant.add(t);
            }
            if (relevant.size() >= 6)
            {
                if (getMyBuying(sym) == 0)
                {
                    long mn = Integer.MAX_VALUE;
                    for (Trade t : relevant)
                        mn = Math.min(mn, t.price);
                    putBuyOrder(sym, mn - margin, TRADE_UNIT);
                }
                if (getMySelling(sym) == 0)
                {
                    long mx = Integer.MIN_VALUE;
                    for (Trade t : relevant)
                        mx = Math.max(mx, t.price);
                    putSellOrder(sym, mx + margin, TRADE_UNIT);
                }
            }
        }
        ArrayList<Offer> bads = new ArrayList<Offer>();
        for (Offer o : myOffers) {
            if (System.currentTimeMillis() - o.makeTime > 600)
                bads.add(o);
        }
        for (Offer o : bads)
            cancelOffer(o);
    }

    static long getMyBuying(String sym)
    {
        long tot = 0;
        for (Offer o : myOffers)
            if (o.symbol.equals(sym) && o.buyOrSell.equals(BUY))
                tot += o.amount;
        return tot;
    }

    static long getMySelling(String sym)
    {
        long tot = 0;
        for (Offer o : myOffers)
            if (o.symbol.equals(sym) && o.buyOrSell.equals("SELL"))
                tot += o.amount;
        return tot;
    }

    static void addHolding(String sym, long gain)
    {
        ourHoldings.put(sym, gain + ourHoldings.get(sym));
    }

    static void fillOurOffer(String[] update)
    {
        System.out.println("Filled: " + Arrays.toString(update));
        String sym = update[2];
        String type = update[3];
        long price = Long.parseLong(update[4]);
        long amount = Long.parseLong(update[5]);
        int mul = type.equals(BUY) ? 1 : -1;
        addHolding(sym, amount * mul);
        ourMoney += price * amount * mul * -1;
        int q = getOfferIndexByID(Long.parseLong(update[1]));
        if (q >= 0)
        {
            Offer o = myOffers.get(q);
            myOffers.set(q, new Offer(
                o.identifier,
                o.symbol,
                o.buyOrSell,
                o.price,
                o.amount - amount));
            if (myOffers.get(q).amount <= 0)
                myOffers.remove(q);
        }
    }

    static int getOfferIndexByID(long id)
    {
        for (int i = 0; i < myOffers.size(); i++)
            if (myOffers.get(i).identifier == id)
                return i;
        return -1;
    }

    static void clearOfferWithID(long id)
    {
        int q = getOfferIndexByID(id);
        if (q >= 0)
        {
            theBook.getEntry(myOffers.get(q).symbol).frozen = true;
            myOffers.remove(q);
        }
    }

    static void updateBook(String[] update)
    {
        try {
            String sym = update[1];
            SymbolEntry en = theBook.getEntry(sym);
            en.clear();
            String mode = "NUL";
            for (int i = 2; i < update.length; i++)
            {
                if (update[i].equals(BUY) || update[i].equals("SELL"))
                    mode = update[i];
                else
                {
                    if (mode.equals(BUY))
                        en.buys.add(parseOrder(update[i]));
                    else if (mode.equals("SELL"))
                        en.sells.add(parseOrder(update[i]));
                    else
                        throw new RuntimeException();
                }
            }
        }
        catch (Throwable t) {
            System.err.println("Error updating book: " + t);
        }
    }

    static Order parseOrder(String s)
    {
        String[] arr = s.trim().split(":");
        return new Order(
            Long.parseLong(arr[0]),
            Long.parseLong(arr[1]));
    }

    static class Order
    {
        final long unitCount;
        final long price;

        Order(long a, long b) {
            this.unitCount = b;
            this.price = a;
        }
    }

    static class SymbolEntry
    {
        ArrayList<Order> buys = new ArrayList<>();
        ArrayList<Order> sells = new ArrayList<>();
        boolean frozen = false;

        void clear() {
            buys =  new ArrayList<Order>();
            sells =  new ArrayList<Order>();
            frozen = false;
        }
    }

    static class Book {
        private Map<String, SymbolEntry> smap = new HashMap<>();
        Book() {
            for (String s : ALL_STOCKS)
                smap.put(s, new SymbolEntry());
        }
        SymbolEntry getEntry(String s) {
            return smap.get(s);
        }
    }

    static Trade parseTrade(String[] update)
    {
        return new Trade(
            update[1],
            Long.parseLong(update[2]),
            Long.parseLong(update[3]),
            System.currentTimeMillis()
        );
    }

    static class Trade {
        final String symbol;
        final long price;
        final long amount;
        final long time;
        Trade(String a, long b, long c, long d) {
            this.symbol = a;
            this.price = b;
            this.amount = c;
            this.time = d;
        }
    }

    static class Offer {
        final long identifier;
        final String symbol;
        final String buyOrSell;
        final long price;
        final long amount;
        final long makeTime;
        Offer(long a, String b, String c, long d, long e) {
            this.identifier = a;
            this.symbol = b;
            this.buyOrSell = c;
            this.price = d;
            this.amount = e;
            this.makeTime = System.currentTimeMillis();
        }
        double getAggro(Map<String, PriceData> m)
        {
            PriceData dat = m.get(symbol);
            if (dat == null)
                return 0;
            return Math.abs(price - dat.mean);
        }
        boolean isBad(Map<String, PriceData> m)
        {
            PriceData dat = m.get(symbol);
            if (dat == null)
                return true;
            if (BUY.equals(buyOrSell))
                return price > dat.mean;
            else
                return price < dat.mean; 
        }
    }
}