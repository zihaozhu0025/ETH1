import java.util.*;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Socket;

public class Bot
{
    static final String TEAMNAME = "GOLDMANHACKS";
    static final String BUY = "BUY";
    static final String SELL = "SELL";
    static final String[] ALL_STOCKS = {"BOND", "VALBZ", "VALE", "GS", "MS", "WFC", "XLF"};
    static final long POS_LIM = 50;
    static final double MIN_MARGIN = 0.02;
    static final double MAX_MARGIN = 0.1;

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
                System.err.printf("The exchange replied: %s\n", raw_reply.trim());
                String[] reply = raw_reply.split("\\s+");
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
                        System.err.println("REJECT!");
                        break;
                    case "FILL":
                        fillOurOffer(reply);
                        break;
                    case "OUT":
                        clearOfferWithID(Long.parseLong(reply[1]));
                        break;
                    default:
                        System.err.println("Unrecognized message type: " + reply[0]);
                }
                toPrint = new ArrayList<String>();
                makeSomeMoney();
                for (String s : toPrint)
                {
                    System.err.println("We sent: " + s);
                    System.out.println("We sent: " + s);
                    to_exchange.println(s);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace(System.out);
        }
    }

    static void putBuyOrder(String sym, long price, long amount)
    {
        if (amount <= 0)
            return;
        long iden = nextID++;
        myOffers.add(new Offer(
            iden,
            sym,
            BUY,
            price,
            amount));
        toPrint.add("ADD " + iden + " " + sym + " " + BUY + " " + price + " " + amount);
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

    static void convert (String sym, long amount)
    {
        if (amount <= 0)
            return;
        long iden = nextID++;
        toPrint.add("CONVERT" + " " + iden + " " + sym + " " + amount);    
    }

    static void makeSomeMoney()
    {
        // putBuyOrder("BOND", 1000, 50 - getMyBuying("BOND"));
        // putSellOrder("BOND", 1001, 50 - getMySelling("BOND"));
        Map<String, PriceData> pricing = new HashMap<>();
        for (String symbol : ALL_STOCKS)
            pricing.put(symbol, getFairPrice(symbol));
        for (String symbol : ALL_STOCKS)
        {
            PriceData fairPrice = pricing.get(symbol);
            if (fairPrice == null)
                continue;
            tradeAt(symbol, fairPrice, BUY);
            tradeAt(symbol, fairPrice, SELL);
        }
        Offer mostYOLO = null;
        ArrayList<Offer> bads = new ArrayList<Offer>();
        for (Offer o : myOffers) {
            if (mostYOLO == null || o.getAggro(pricing) > mostYOLO.getAggro(pricing))
                mostYOLO = o;
            if (o.isBad(pricing))
                bads.add(o);
        }
        for (Offer o : bads)
            cancelOffer(o);
        if (mostYOLO != null && myOffers.size() >= 15)
            cancelOffer(mostYOLO);
        // if(10 * getMeanFairPrice("XLF") + 100 < 3 * getMeanFairPrice("BOND") + 2 * getMeanFairPrice("GS") + 3 * getMeanFairPrice("MS") + 2 * getMeanFairPrice("WFC")
        //     && getMeanFairPrice("XLF") >= 0
        //     && getMeanFairPrice("BOND") >= 0
        //     && getMeanFairPrice("GS") >= 0
        //     && getMeanFairPrice("MS") >= 0
        //     && getMeanFairPrice("WFC") >= 0)
        // {
        //     convert("XLF", 5);   
        // }
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
            myOffers.remove(q);
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
            this.unitCount = a;
            this.price = b;
        }
    }

    static class SymbolEntry
    {
        ArrayList<Order> buys = new ArrayList<>();
        ArrayList<Order> sells = new ArrayList<>();

        void clear() {
            buys =  new ArrayList<Order>();
            sells =  new ArrayList<Order>();
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
        Offer(long a, String b, String c, long d, long e) {
            this.identifier = a;
            this.symbol = b;
            this.buyOrSell = c;
            this.price = d;
            this.amount = e;
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