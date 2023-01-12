import entity.Settlement;
import parser.FiasParser;

import java.nio.file.Paths;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        var parser = new FiasParser(
                Paths.get("fias_xml/addrObj.XML"),
                Paths.get("fias_xml/addrObjParams.XML"),
                Paths.get("fias_xml/admHier.XML")
        );

        parser.parse();

        List<Settlement> settlements = parser.getSettlements();
        settlements.forEach(System.out::println);
        System.out.println("Кол-во населенных пунктов: " + settlements.size());
    }
}

