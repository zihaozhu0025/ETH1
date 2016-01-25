import java.util.*;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Socket;

public class OldBot
{
    static final String TEAMNAME = "GOLDMANHACKS";
    static final String BUY = "BUY";
    static final String SELL = "SELL";
    static final String[] ALL_STOCKS = {"BOND", "VALBZ", "VALE", "GS", "MS", "WFC", "XLF"};
    static final long POS_LIM = 50;

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
            // Socket skt = new Socket("test-exch-" + TEAMNAME, 20001);
            Socket skt = new Socket("production", 20000);
            BufferedReader from_exchange = new BufferedReader(new InputStreamReader(skt.getInputStream()));
            PrintWriter to_exchange = new PrintWriter(skt.getOutputStream(), true);

            to_exchange.println("HELLO " + TEAMNAME);
            while (true)
            {
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

    static void makeSomeMoney()
    {
        putBuyOrder("BOND", 1000, 25 - getMyBuying("BOND"));
        putSellOrder("BOND", 1001, Math.min(50L, ourHoldings.get("BOND")) - getMySelling("BOND"));
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
    }
}