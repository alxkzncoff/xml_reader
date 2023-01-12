package parser;

import entity.District;
import entity.DistrictType;
import entity.Settlement;
import lombok.extern.slf4j.Slf4j;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.*;

/**
 * Парсер данных из БД ФИАС.
 * Данные собираются из трех файлов: AS_ADDR_OBJ, AS_ADDR_OBJ_PARAMS, AS_ADM_HIERARCHY.
 * @author Aleksandr Kuznetsov.
 */
@Slf4j
public class FiasParser {

    private Path addrObj;
    private Path addrObjParams;
    private Path admHier;

    private final List<District> districts = new ArrayList<>();
    private final List<Settlement> settlements = new ArrayList<>();

    public FiasParser(Path addrObj, Path addrObjParams, Path admHier) {
        this.addrObj = addrObj;
        this.addrObjParams = addrObjParams;
        this.admHier = admHier;
    }

    public Path getAddrObj() {
        return addrObj;
    }

    public void setAddrObj(Path addrObj) {
        this.addrObj = addrObj;
    }

    public Path getAddrObjParams() {
        return addrObjParams;
    }

    public void setAddrObjParams(Path addrObjParams) {
        this.addrObjParams = addrObjParams;
    }

    public Path getAdmHier() {
        return admHier;
    }

    public void setAdmHier(Path admHier) {
        this.admHier = admHier;
    }

    public List<District> getDistricts() {
        return List.copyOf(districts);
    }

    public List<Settlement> getSettlements() {
        return List.copyOf(settlements);
    }

    private static XMLEventReader getReader(Path path) throws FileNotFoundException, XMLStreamException {
        return XMLInputFactory.newInstance().createXMLEventReader(new FileInputStream(path.toFile()));
    }

    public void parse() {
        findAllDistricts();
        List<Integer> objectsIds = findAllObjectId();
        List<List<Integer>> chains = findAddressesChains(objectsIds);
        findAllSettlements(chains);
    }

    /**
     * Парсит районы и города.
     */
    private void findAllDistricts() {

        try {
            XMLEventReader reader = getReader(addrObj);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    var element = event.asStartElement();
                    if ("OBJECT".equals(element.getName().getLocalPart())) {
                        var name = element.getAttributeByName(new QName("NAME")).getValue();
                        var level = element.getAttributeByName(new QName("LEVEL")).getValue();
                        var typeName = element.getAttributeByName(new QName("TYPENAME")).getValue();
                        var objectId = Integer.parseInt(element.getAttributeByName(new QName("OBJECTID")).getValue());
                        if (level.equals("2")) {
                            districts.add(District.builder()
                                    .id(objectId)
                                    .name(name)
                                    .type(DistrictType.DISTRICT)
                                    .build());
                        }
                        if (level.equals("5") && typeName.equals("г")) {
                            districts.add(District.builder()
                                    .id(objectId)
                                    .name(name)
                                    .type(DistrictType.CITY)
                                    .build());
                        }
                    }
                }
            }
            reader.close();
        } catch (FileNotFoundException | XMLStreamException e) {
            log.error(e.getMessage());
        }
        /* Эти данные пришлось в ручную добавить. По другому никак. */
        districts.add(District.builder().id(969166).type(DistrictType.CITY).name("ЗАТО Комаровский").build());
    }

    /**
     * Парсит ObjectId всех населенных пунктов (от городов до снт).
     * Поиск ведется по трем уровням (LEVEL): 5 - города;
     *                                        6 - другие наследенные пункты (деревни, села и т.д.);
     *                                        7 - снт (стоит дополнительный фильтр).
     * @return Мапу с id и названием насленного пункта.
     */
    private List<Integer> findAllObjectId() {
        List<Integer> objectsIds = new ArrayList<>();

        try {
            XMLEventReader reader = getReader(addrObj);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    var element = event.asStartElement();
                    if ("OBJECT".equals(element.getName().getLocalPart())) {
                        var level = element.getAttributeByName(new QName("LEVEL")).getValue();
                        var typeName = element.getAttributeByName(new QName("TYPENAME")).getValue();
                        var objectId = Integer.parseInt(element.getAttributeByName(new QName("OBJECTID")).getValue());
                        var isActual = element.getAttributeByName(new QName("ISACTUAL")).getValue();
                        var isActive = element.getAttributeByName(new QName("ISACTIVE")).getValue();
                        if (level.equals("5") || level.equals("6") || (level.equals("7") && typeName.equals("снт"))) {
                            if (isActual.equals("1") && isActive.equals("1")) {
                                objectsIds.add(objectId);
                            }
                        }
                    }
                }
            }
            reader.close();
        } catch (FileNotFoundException | XMLStreamException e) {
            log.error(e.getMessage());
        }
        return objectsIds;
    }

    /**
     * Парсит цепочки ObjectId, по которым можно восстановить адресс объекта.
     * @param objectsIds список OBJECTID объектов, у которых нужно восстановить цепочку адреса.
     */
    private List<List<Integer>> findAddressesChains(List<Integer> objectsIds) {
        List<List<Integer>> chains = new ArrayList<>();

        try {
            XMLEventReader reader = getReader(admHier);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    var element = event.asStartElement();
                    if ("ITEM".equals(element.getName().getLocalPart())) {
                        var objectId = Integer.parseInt(element.getAttributeByName(new QName("OBJECTID")).getValue());
                        var path = element.getAttributeByName(new QName("PATH")).getValue();
                        if (objectsIds.contains(objectId)) {
                            chains.add(Arrays.stream(path.split("\\."))
                                    .map(Integer::parseInt)
                                    .toList());
                        }
                    }
                }
            }
            reader.close();
        } catch (FileNotFoundException | XMLStreamException e) {
            log.error(e.getMessage());
        }
        return chains;
    }

    /**
     * Парсит населенные пункты и к каким районам они относятся (от городов до снт).
     * Поиск ведется по трем уровням (LEVEL): 5 - города;
     *                                        6 - другие наследенные пункты (деревни, села и т.д.);
     *                                        7 - снт (стоит дополнительный фильтр).
     * @param chains список с цепочками адресов.
     */
    private void findAllSettlements(List<List<Integer>> chains) {
        District district = new District();

        try {
            XMLEventReader reader = getReader(addrObj);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    var element = event.asStartElement();
                    if ("OBJECT".equals(element.getName().getLocalPart())) {
                        var level = element.getAttributeByName(new QName("LEVEL")).getValue();
                        var typeName = element.getAttributeByName(new QName("TYPENAME")).getValue();
                        var name = element.getAttributeByName(new QName("NAME")).getValue();
                        var objectId = Integer.parseInt(element.getAttributeByName(new QName("OBJECTID")).getValue());
                        var isActual = element.getAttributeByName(new QName("ISACTUAL")).getValue();
                        var isActive = element.getAttributeByName(new QName("ISACTIVE")).getValue();
                        if (level.equals("5") || level.equals("6") || (level.equals("7") && typeName.equals("снт"))) {
                            if (isActual.equals("1") && isActive.equals("1")) {
                                for (List<Integer> chain: chains) {
                                    if (chain.get(chain.size() - 1) == objectId) {
                                        for (District d: districts) {
                                            if (d.getId() == chain.get(1)) {
                                                district = d;
                                            }
                                        }
                                        settlements.add(Settlement.builder()
                                                .id(chain.get(chain.size() - 1))
                                                .name(typeName + " " + name)
                                                .district(district)
                                                .build());
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            reader.close();
        } catch (FileNotFoundException | XMLStreamException e) {
            log.error(e.getMessage());
        }
    }
}
