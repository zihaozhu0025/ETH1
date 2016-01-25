
import java.util.*;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Socket;

public class KindaChangedBot {
    static int[] fairPrices = new int[7];
    static int[][] last10 = new int[7][10];
    static int[] numTraded = new int[7];
    static int[] dFairPrices = new int[7];
    static final String TEAMNAME = "GOLDMANHACKS";
    static final String BUY = "BUY";
    static final String SELL = "SELL";
    static final String[] ALL_STOCKS = { "BOND", "VALBZ", "VALE", "GS", "MS", "WFC", "XLF" };
    static final int POS_LIM = 50;

    static Book theBook = new Book();
    static ArrayList<Trade> pastTrades = new ArrayList<>();
    static ArrayList<Offer> myOffers = new ArrayList<>();
    static boolean areWeOpen = false;
    static int ourMoney = 0;
    static Map<String, Integer> ourHoldings = new HashMap<>();

    static {
        for (String s : ALL_STOCKS)
            ourHoldings.put(s, 0);
    }

    static int nextID = 1337;

    static ArrayList<String> toPrint;

    public static void main(String[] args) {
        try {
            Socket skt = new Socket("test-exch-" + TEAMNAME, 20001);
            // Socket skt = new Socket("production", 20001);
            BufferedReader from_exchange = new BufferedReader(new InputStreamReader(skt.getInputStream()));
            PrintWriter to_exchange = new PrintWriter(skt.getOutputStream(), true);

            to_exchange.println("HELLO " + TEAMNAME);
            while (true) {
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
                    clearOfferWithID(Integer.parseInt(reply[1]));
                    break;
                default:
                    System.err.println("Unrecognized message type: " + reply[0]);
                }
                toPrint = new ArrayList<String>();
                makeSomeMoney();
                for (String s : toPrint) {
                    System.out.println("We sent: " + s);
                    to_exchange.println(s);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }

    static void putBuyOrder(String sym, int price, int amount) {
        if (amount <= 0)
            return;
        int iden = nextID++;
        myOffers.add(new Offer(iden, sym, BUY, price, amount));
        toPrint.add("ADD " + iden + " " + sym + " " + BUY + " " + price + " " + amount);
    }
    static void putConvertToOrder(String sym, int price, int amount)
    {
        if(amount <=0)
            return;
        int iden = nextID++;
        //toPrint.add("CONVERT "+ iden + " " + BUY + " "+ amount)
    }
    /**
     * 
     * @param sym
     *            the stock we're selling at
     * @param price
     *            the price we're selling at
     * @param amount
     *            the number of stock we're selling
     */
    static void putSellOrder(String sym, int price, int amount) {
        if (amount <= 0)
            return;
        int iden = nextID++;
        myOffers.add(new Offer(iden, sym, SELL, price, amount));
        toPrint.add("ADD " + iden + " " + sym + " " + SELL + " " + price + " " + amount);
    }


    static void makeSomeMoney() {
        putBuyOrder("BOND", 1000, 25 - getMyBuying("BOND"));
        putSellOrder("BOND", Math.max(1001,fairPrices[0] + 1), Math.min(50, ourHoldings.get("BOND")) - getMySelling("BOND"));
        //goes through all stocks that aren't bonds or packages.
        for (int i = 1; i < 6; i++) {
            if (numTraded[i] < 10)
                continue;
            if (dFairPrices[i] > 0)
                putBuyOrder(ALL_STOCKS[i], fairPrices[i] - 1, 25 - getMyBuying(ALL_STOCKS[i]));

            if (dFairPrices[i] < 0)
                putSellOrder(ALL_STOCKS[i], fairPrices[i] + 1,
                        Math.min(50, ourHoldings.get(ALL_STOCKS[i]) - getMySelling(ALL_STOCKS[i])));

        }
        //If package costs less than sum of its parts aint with conversion rate, buy package and sell parts
        // if(fairPrices[7]+100<(3*fairPrices[0]+2*fairPrices[3]+3*fairPrices[4]+2*fairPrices[5]))
        // {
        //     int numStocks=25-getMyBuying(ALL_STOCKS[7]);
            
        //     putBuyOrder(ALL_STOCKS[7],fairPrices[7]-1,numStocks);
        //     if(dFairPrices[0]<0)
        //         putSellOrder(ALL_STOCKS[0],fairPrices[0]+1,3*numStocks);
        //     if(dFairPrices[3]<0)
        //         putSellOrder(ALL_STOCKS[3],fairPrices[3]+1,2*numStocks);
        //     if(dFairPrices[4]<0)
        //         putSellOrder(ALL_STOCKS[4],fairPrices[4],3*numStocks);
        //     if(dFairPrices[5]<0)
        //         putSellOrder(ALL_STOCKS[5],fairPrices[5],2*numStocks);
        // }
    }

    static int getMyBuying(String sym) {
        int tot = 0;
        for (Offer o : myOffers)
            if (o.symbol.equals(sym) && o.buyOrSell.equals(BUY))
                tot += o.amount;
        return tot;
    }

    static int getMySelling(String sym) {
        int tot = 0;
        for (Offer o : myOffers)
            if (o.symbol.equals(sym) && o.buyOrSell.equals("SELL"))
                tot += o.amount;
        return tot;
    }

    static void addHolding(String sym, int gain) {
        ourHoldings.put(sym, gain + ourHoldings.get(sym));
    }

    static void fillOurOffer(String[] update) {
        String sym = update[2];
        String type = update[3];
        int price = Integer.parseInt(update[4]);
        int amount = Integer.parseInt(update[5]);
        int mul = type.equals(BUY) ? 1 : -1;
        addHolding(sym, amount * mul);
        ourMoney += price * amount * mul * -1;
        int q = getOfferIndexByID(Integer.parseInt(update[1]));
        if (q >= 0) {
            Offer o = myOffers.get(q);
            myOffers.set(q, new Offer(o.identifier, o.symbol, o.buyOrSell, o.price, o.amount - amount));
            if (myOffers.get(q).amount <= 0)
                myOffers.remove(q);
        }
    }

    static int getOfferIndexByID(int id) {
        for (int i = 0; i < myOffers.size(); i++)
            if (myOffers.get(i).identifier == id)
                return i;
        return -1;
    }

    static void clearOfferWithID(int id) {
        int q = getOfferIndexByID(id);
        if (q >= 0)
            myOffers.remove(q);
    }

    static void updateBook(String[] update) {
        try {
            String sym = update[1];
            SymbolEntry en = theBook.getEntry(sym);
            en.clear();
            String mode = "NUL";
            for (int i = 2; i < update.length; i++) {
                if (update[i].equals(BUY) || update[i].equals("SELL"))
                    mode = update[i];
                else {
                    int stockNum = indexOf(ALL_STOCKS, sym);
                    updateFairPrices(stockNum, parseOrder(update[i]).price);

                    if (mode.equals(BUY))
                        en.buys.add(parseOrder(update[i]));

                    else if (mode.equals("SELL"))
                        en.sells.add(parseOrder(update[i]));

                    else
                        throw new RuntimeException();
                }
            }
        } catch (Throwable t) {
            System.err.println("Error updating book: " + t);
            t.printStackTrace();
        }
    }

    static Order parseOrder(String s) {
        String[] arr = s.trim().split(":");
        return new Order(Integer.parseInt(arr[0]), Integer.parseInt(arr[1]));
    }

    static class Order {
        final int unitCount;
        final int price;

        Order(int a, int b) {
            this.unitCount = a;
            this.price = b;
        }
    }

    static class SymbolEntry {
        ArrayList<Order> buys = new ArrayList<>();
        ArrayList<Order> sells = new ArrayList<>();

        void clear() {
            buys = new ArrayList<Order>();
            sells = new ArrayList<Order>();
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

    static Trade parseTrade(String[] update) {
        return new Trade(update[1], Integer.parseInt(update[2]), Integer.parseInt(update[3]), System.currentTimeMillis());
    }

    static class Trade {
        final String symbol;
        final int price;
        final int amount;
        final long time;

        Trade(String a, int b, int c, long d) {
            this.symbol = a;
            this.price = b;
            this.amount = c;
            this.time = d;
        }
    }

    static class Offer {
        final int identifier;
        final String symbol;
        final String buyOrSell;
        final int price;
        final int amount;

        Offer(int a, String b, String c, int d, int e) {
            this.identifier = a;
            this.symbol = b;
            this.buyOrSell = c;
            this.price = d;
            this.amount = e;
        }
    }

    public static int indexOf(String[] names, String ele) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(ele))
                return i;
        }
        return -1;
    }

    public static void moveDown(int[] prices, int p) {
        int r = p;
        int t;
        for (int i = 9; i >= 0; i--) {
            t = prices[i];
            prices[i] = r;
            r = t;
        }
    }

    public static void updateFairPrices(int stockNum, int price) {
        int newFP;
        if (numTraded[stockNum] < 10) {
            last10[stockNum][numTraded[stockNum]] = price;
            numTraded[stockNum]++;
            // newFP = (fairPrices[stockNum] + price) / numTraded[stockNum];

        } else {
            // newFP = (fairPrices[stockNum] + price - last10[stockNum][0]) / 10;
            moveDown(last10[stockNum], price);
        }
        int sum = 0;
        for (int i = 0; i < numTraded[stockNum]; i++)
            sum += last10[stockNum][i];
        newFP = sum / numTraded[stockNum];
        dFairPrices[stockNum] = newFP - fairPrices[stockNum];
        fairPrices[stockNum] = newFP;
    }
}
