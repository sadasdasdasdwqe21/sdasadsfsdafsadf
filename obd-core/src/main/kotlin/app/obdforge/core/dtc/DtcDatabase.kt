package app.obdforge.core.dtc

/**
 * SAE J2012 generic DTC descriptions. Manufacturer-specific codes (P1xxx, P3000-P33FF, B1/C1/U1
 * etc.) are not invented here: they are looked up only in vehicle profiles the user imports or
 * creates, and otherwise shown as "manufacturer-specific, description not in database".
 */
object DtcDatabase {
    private val generic = HashMap<String, String>()
    private val extra = HashMap<String, MutableMap<String, String>>() // make -> code -> text

    fun describe(code: String, make: String? = null): String? {
        val c = code.uppercase()
        if (make != null) extra[make.lowercase()]?.get(c)?.let { return it }
        generic[c]?.let { return it }
        return familyDescription(c)
    }

    /** True when the description comes from the generic table (or a pattern) rather than a profile. */
    fun isKnown(code: String) = generic.containsKey(code.uppercase()) || familyDescription(code.uppercase()) != null

    fun addManufacturerCodes(make: String, codes: Map<String, String>) {
        extra.getOrPut(make.lowercase()) { HashMap() }.putAll(codes.mapKeys { it.key.uppercase() })
    }

    fun manufacturerCodeCount(make: String) = extra[make.lowercase()]?.size ?: 0

    val size: Int get() = generic.size

    fun search(query: String): List<Pair<String, String>> {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        return generic.entries.filter { it.key.startsWith(q) || it.value.uppercase().contains(q) }
            .sortedBy { it.key }.take(200).map { it.key to it.value }
    }

    /** Fallback text for a generic code we have no entry for, built only from the J2012 structure. */
    private fun familyDescription(c: String): String? {
        if (c.length != 5 || c[0] != 'P' || c[1] != '0') return null
        return null
    }

    /** A structural description usable for any code (system + generic/manufacturer + subsystem). */
    fun structure(code: String): String {
        val d = Dtc(code.uppercase(), DtcKind.STORED, 0)
        val scope = if (d.isGeneric) "SAE generic" else "Manufacturer-specific"
        return "${d.system.label} · $scope · ${d.subsystem}"
    }

    private const val TABLE = """
P0001|Fuel Volume Regulator Control Circuit/Open
P0002|Fuel Volume Regulator Control Circuit Range/Performance
P0003|Fuel Volume Regulator Control Circuit Low
P0004|Fuel Volume Regulator Control Circuit High
P0005|Fuel Shutoff Valve A Control Circuit/Open
P0010|Intake Camshaft Position Actuator Circuit/Open (Bank 1)
P0011|Intake Camshaft Position - Timing Over-Advanced or System Performance (Bank 1)
P0012|Intake Camshaft Position - Timing Over-Retarded (Bank 1)
P0013|Exhaust Camshaft Position Actuator Circuit/Open (Bank 1)
P0014|Exhaust Camshaft Position - Timing Over-Advanced or System Performance (Bank 1)
P0015|Exhaust Camshaft Position - Timing Over-Retarded (Bank 1)
P0016|Crankshaft Position - Camshaft Position Correlation (Bank 1 Sensor A)
P0017|Crankshaft Position - Camshaft Position Correlation (Bank 1 Sensor B)
P0018|Crankshaft Position - Camshaft Position Correlation (Bank 2 Sensor A)
P0019|Crankshaft Position - Camshaft Position Correlation (Bank 2 Sensor B)
P0020|Intake Camshaft Position Actuator Circuit/Open (Bank 2)
P0021|Intake Camshaft Position - Timing Over-Advanced or System Performance (Bank 2)
P0022|Intake Camshaft Position - Timing Over-Retarded (Bank 2)
P0023|Exhaust Camshaft Position Actuator Circuit/Open (Bank 2)
P0024|Exhaust Camshaft Position - Timing Over-Advanced or System Performance (Bank 2)
P0025|Exhaust Camshaft Position - Timing Over-Retarded (Bank 2)
P0030|HO2S Heater Control Circuit (Bank 1 Sensor 1)
P0031|HO2S Heater Control Circuit Low (Bank 1 Sensor 1)
P0032|HO2S Heater Control Circuit High (Bank 1 Sensor 1)
P0033|Turbocharger/Supercharger Bypass Valve Control Circuit
P0034|Turbocharger/Supercharger Bypass Valve Control Circuit Low
P0035|Turbocharger/Supercharger Bypass Valve Control Circuit High
P0036|HO2S Heater Control Circuit (Bank 1 Sensor 2)
P0037|HO2S Heater Control Circuit Low (Bank 1 Sensor 2)
P0038|HO2S Heater Control Circuit High (Bank 1 Sensor 2)
P0040|O2 Sensor Signals Swapped Bank 1 Sensor 1 / Bank 2 Sensor 1
P0041|O2 Sensor Signals Swapped Bank 1 Sensor 2 / Bank 2 Sensor 2
P0042|HO2S Heater Control Circuit (Bank 1 Sensor 3)
P0043|HO2S Heater Control Circuit Low (Bank 1 Sensor 3)
P0044|HO2S Heater Control Circuit High (Bank 1 Sensor 3)
P0050|HO2S Heater Control Circuit (Bank 2 Sensor 1)
P0051|HO2S Heater Control Circuit Low (Bank 2 Sensor 1)
P0052|HO2S Heater Control Circuit High (Bank 2 Sensor 1)
P0053|HO2S Heater Resistance (Bank 1 Sensor 1)
P0054|HO2S Heater Resistance (Bank 1 Sensor 2)
P0055|HO2S Heater Resistance (Bank 1 Sensor 3)
P0056|HO2S Heater Control Circuit (Bank 2 Sensor 2)
P0057|HO2S Heater Control Circuit Low (Bank 2 Sensor 2)
P0058|HO2S Heater Control Circuit High (Bank 2 Sensor 2)
P0059|HO2S Heater Resistance (Bank 2 Sensor 1)
P0060|HO2S Heater Resistance (Bank 2 Sensor 2)
P0068|MAP/MAF - Throttle Position Correlation
P0069|Manifold Absolute Pressure - Barometric Pressure Correlation
P0070|Ambient Air Temperature Sensor Circuit
P0071|Ambient Air Temperature Sensor Range/Performance
P0072|Ambient Air Temperature Sensor Circuit Low
P0073|Ambient Air Temperature Sensor Circuit High
P0074|Ambient Air Temperature Sensor Circuit Intermittent
P0075|Intake Valve Control Solenoid Circuit (Bank 1)
P0076|Intake Valve Control Solenoid Circuit Low (Bank 1)
P0077|Intake Valve Control Solenoid Circuit High (Bank 1)
P0078|Exhaust Valve Control Solenoid Circuit (Bank 1)
P0081|Intake Valve Control Solenoid Circuit (Bank 2)
P0087|Fuel Rail/System Pressure - Too Low
P0088|Fuel Rail/System Pressure - Too High
P0089|Fuel Pressure Regulator 1 Performance
P0090|Fuel Pressure Regulator 1 Control Circuit
P0091|Fuel Pressure Regulator 1 Control Circuit Low
P0092|Fuel Pressure Regulator 1 Control Circuit High
P0093|Fuel System Leak Detected - Large Leak
P0094|Fuel System Leak Detected - Small Leak
P0095|Intake Air Temperature Sensor 2 Circuit
P0096|Intake Air Temperature Sensor 2 Circuit Range/Performance
P0097|Intake Air Temperature Sensor 2 Circuit Low
P0098|Intake Air Temperature Sensor 2 Circuit High
P0100|Mass or Volume Air Flow Circuit Malfunction
P0101|Mass or Volume Air Flow Circuit Range/Performance Problem
P0102|Mass or Volume Air Flow Circuit Low Input
P0103|Mass or Volume Air Flow Circuit High Input
P0104|Mass or Volume Air Flow Circuit Intermittent
P0105|Manifold Absolute Pressure/Barometric Pressure Circuit Malfunction
P0106|Manifold Absolute Pressure/Barometric Pressure Circuit Range/Performance Problem
P0107|Manifold Absolute Pressure/Barometric Pressure Circuit Low Input
P0108|Manifold Absolute Pressure/Barometric Pressure Circuit High Input
P0109|Manifold Absolute Pressure/Barometric Pressure Circuit Intermittent
P0110|Intake Air Temperature Circuit Malfunction
P0111|Intake Air Temperature Circuit Range/Performance Problem
P0112|Intake Air Temperature Circuit Low Input
P0113|Intake Air Temperature Circuit High Input
P0114|Intake Air Temperature Circuit Intermittent
P0115|Engine Coolant Temperature Circuit Malfunction
P0116|Engine Coolant Temperature Circuit Range/Performance Problem
P0117|Engine Coolant Temperature Circuit Low Input
P0118|Engine Coolant Temperature Circuit High Input
P0119|Engine Coolant Temperature Circuit Intermittent
P0120|Throttle/Pedal Position Sensor/Switch A Circuit Malfunction
P0121|Throttle/Pedal Position Sensor/Switch A Circuit Range/Performance Problem
P0122|Throttle/Pedal Position Sensor/Switch A Circuit Low Input
P0123|Throttle/Pedal Position Sensor/Switch A Circuit High Input
P0124|Throttle/Pedal Position Sensor/Switch A Circuit Intermittent
P0125|Insufficient Coolant Temperature for Closed Loop Fuel Control
P0126|Insufficient Coolant Temperature for Stable Operation
P0127|Intake Air Temperature Too High
P0128|Coolant Thermostat (Coolant Temperature Below Thermostat Regulating Temperature)
P0130|O2 Sensor Circuit Malfunction (Bank 1 Sensor 1)
P0131|O2 Sensor Circuit Low Voltage (Bank 1 Sensor 1)
P0132|O2 Sensor Circuit High Voltage (Bank 1 Sensor 1)
P0133|O2 Sensor Circuit Slow Response (Bank 1 Sensor 1)
P0134|O2 Sensor Circuit No Activity Detected (Bank 1 Sensor 1)
P0135|O2 Sensor Heater Circuit Malfunction (Bank 1 Sensor 1)
P0136|O2 Sensor Circuit Malfunction (Bank 1 Sensor 2)
P0137|O2 Sensor Circuit Low Voltage (Bank 1 Sensor 2)
P0138|O2 Sensor Circuit High Voltage (Bank 1 Sensor 2)
P0139|O2 Sensor Circuit Slow Response (Bank 1 Sensor 2)
P0140|O2 Sensor Circuit No Activity Detected (Bank 1 Sensor 2)
P0141|O2 Sensor Heater Circuit Malfunction (Bank 1 Sensor 2)
P0142|O2 Sensor Circuit Malfunction (Bank 1 Sensor 3)
P0143|O2 Sensor Circuit Low Voltage (Bank 1 Sensor 3)
P0144|O2 Sensor Circuit High Voltage (Bank 1 Sensor 3)
P0145|O2 Sensor Circuit Slow Response (Bank 1 Sensor 3)
P0146|O2 Sensor Circuit No Activity Detected (Bank 1 Sensor 3)
P0147|O2 Sensor Heater Circuit Malfunction (Bank 1 Sensor 3)
P0148|Fuel Delivery Error
P0149|Fuel Timing Error
P0150|O2 Sensor Circuit Malfunction (Bank 2 Sensor 1)
P0151|O2 Sensor Circuit Low Voltage (Bank 2 Sensor 1)
P0152|O2 Sensor Circuit High Voltage (Bank 2 Sensor 1)
P0153|O2 Sensor Circuit Slow Response (Bank 2 Sensor 1)
P0154|O2 Sensor Circuit No Activity Detected (Bank 2 Sensor 1)
P0155|O2 Sensor Heater Circuit Malfunction (Bank 2 Sensor 1)
P0156|O2 Sensor Circuit Malfunction (Bank 2 Sensor 2)
P0157|O2 Sensor Circuit Low Voltage (Bank 2 Sensor 2)
P0158|O2 Sensor Circuit High Voltage (Bank 2 Sensor 2)
P0159|O2 Sensor Circuit Slow Response (Bank 2 Sensor 2)
P0160|O2 Sensor Circuit No Activity Detected (Bank 2 Sensor 2)
P0161|O2 Sensor Heater Circuit Malfunction (Bank 2 Sensor 2)
P0170|Fuel Trim Malfunction (Bank 1)
P0171|System Too Lean (Bank 1)
P0172|System Too Rich (Bank 1)
P0173|Fuel Trim Malfunction (Bank 2)
P0174|System Too Lean (Bank 2)
P0175|System Too Rich (Bank 2)
P0176|Fuel Composition Sensor Circuit Malfunction
P0177|Fuel Composition Sensor Circuit Range/Performance
P0178|Fuel Composition Sensor Circuit Low Input
P0179|Fuel Composition Sensor Circuit High Input
P0180|Fuel Temperature Sensor A Circuit Malfunction
P0181|Fuel Temperature Sensor A Circuit Range/Performance
P0182|Fuel Temperature Sensor A Circuit Low Input
P0183|Fuel Temperature Sensor A Circuit High Input
P0190|Fuel Rail Pressure Sensor Circuit Malfunction
P0191|Fuel Rail Pressure Sensor Circuit Range/Performance
P0192|Fuel Rail Pressure Sensor Circuit Low Input
P0193|Fuel Rail Pressure Sensor Circuit High Input
P0194|Fuel Rail Pressure Sensor Circuit Intermittent
P0200|Injector Circuit Malfunction
P0201|Injector Circuit Malfunction - Cylinder 1
P0202|Injector Circuit Malfunction - Cylinder 2
P0203|Injector Circuit Malfunction - Cylinder 3
P0204|Injector Circuit Malfunction - Cylinder 4
P0205|Injector Circuit Malfunction - Cylinder 5
P0206|Injector Circuit Malfunction - Cylinder 6
P0207|Injector Circuit Malfunction - Cylinder 7
P0208|Injector Circuit Malfunction - Cylinder 8
P0209|Injector Circuit Malfunction - Cylinder 9
P0210|Injector Circuit Malfunction - Cylinder 10
P0211|Injector Circuit Malfunction - Cylinder 11
P0212|Injector Circuit Malfunction - Cylinder 12
P0213|Cold Start Injector 1 Malfunction
P0214|Cold Start Injector 2 Malfunction
P0215|Engine Shutoff Solenoid Malfunction
P0216|Injection Timing Control Circuit Malfunction
P0217|Engine Overtemperature Condition
P0218|Transmission Over Temperature Condition
P0219|Engine Overspeed Condition
P0220|Throttle/Pedal Position Sensor/Switch B Circuit Malfunction
P0221|Throttle/Pedal Position Sensor/Switch B Circuit Range/Performance Problem
P0222|Throttle/Pedal Position Sensor/Switch B Circuit Low Input
P0223|Throttle/Pedal Position Sensor/Switch B Circuit High Input
P0224|Throttle/Pedal Position Sensor/Switch B Circuit Intermittent
P0225|Throttle/Pedal Position Sensor/Switch C Circuit Malfunction
P0226|Throttle/Pedal Position Sensor/Switch C Circuit Range/Performance Problem
P0227|Throttle/Pedal Position Sensor/Switch C Circuit Low Input
P0228|Throttle/Pedal Position Sensor/Switch C Circuit High Input
P0230|Fuel Pump Primary Circuit Malfunction
P0231|Fuel Pump Secondary Circuit Low
P0232|Fuel Pump Secondary Circuit High
P0233|Fuel Pump Secondary Circuit Intermittent
P0234|Engine Overboost Condition
P0235|Turbocharger Boost Sensor A Circuit Malfunction
P0236|Turbocharger Boost Sensor A Circuit Range/Performance
P0237|Turbocharger Boost Sensor A Circuit Low
P0238|Turbocharger Boost Sensor A Circuit High
P0239|Turbocharger Boost Sensor B Circuit Malfunction
P0243|Turbocharger Wastegate Solenoid A Malfunction
P0244|Turbocharger Wastegate Solenoid A Range/Performance
P0245|Turbocharger Wastegate Solenoid A Low
P0246|Turbocharger Wastegate Solenoid A High
P0247|Turbocharger Wastegate Solenoid B Malfunction
P0251|Injection Pump Fuel Metering Control A Malfunction (Cam/Rotor/Injector)
P0261|Cylinder 1 Injector Circuit Low
P0262|Cylinder 1 Injector Circuit High
P0263|Cylinder 1 Contribution/Balance Fault
P0264|Cylinder 2 Injector Circuit Low
P0265|Cylinder 2 Injector Circuit High
P0266|Cylinder 2 Contribution/Balance Fault
P0267|Cylinder 3 Injector Circuit Low
P0268|Cylinder 3 Injector Circuit High
P0269|Cylinder 3 Contribution/Balance Fault
P0270|Cylinder 4 Injector Circuit Low
P0271|Cylinder 4 Injector Circuit High
P0272|Cylinder 4 Contribution/Balance Fault
P0299|Turbocharger/Supercharger Underboost Condition
P0300|Random/Multiple Cylinder Misfire Detected
P0301|Cylinder 1 Misfire Detected
P0302|Cylinder 2 Misfire Detected
P0303|Cylinder 3 Misfire Detected
P0304|Cylinder 4 Misfire Detected
P0305|Cylinder 5 Misfire Detected
P0306|Cylinder 6 Misfire Detected
P0307|Cylinder 7 Misfire Detected
P0308|Cylinder 8 Misfire Detected
P0309|Cylinder 9 Misfire Detected
P0310|Cylinder 10 Misfire Detected
P0311|Cylinder 11 Misfire Detected
P0312|Cylinder 12 Misfire Detected
P0313|Misfire Detected with Low Fuel
P0314|Single Cylinder Misfire (Cylinder not Specified)
P0315|Crankshaft Position System Variation Not Learned
P0316|Engine Misfire Detected on Startup (First 1000 Revolutions)
P0320|Ignition/Distributor Engine Speed Input Circuit Malfunction
P0321|Ignition/Distributor Engine Speed Input Circuit Range/Performance
P0322|Ignition/Distributor Engine Speed Input Circuit No Signal
P0325|Knock Sensor 1 Circuit Malfunction (Bank 1 or Single Sensor)
P0326|Knock Sensor 1 Circuit Range/Performance (Bank 1 or Single Sensor)
P0327|Knock Sensor 1 Circuit Low Input (Bank 1 or Single Sensor)
P0328|Knock Sensor 1 Circuit High Input (Bank 1 or Single Sensor)
P0329|Knock Sensor 1 Circuit Intermittent (Bank 1 or Single Sensor)
P0330|Knock Sensor 2 Circuit Malfunction (Bank 2)
P0331|Knock Sensor 2 Circuit Range/Performance (Bank 2)
P0332|Knock Sensor 2 Circuit Low Input (Bank 2)
P0333|Knock Sensor 2 Circuit High Input (Bank 2)
P0335|Crankshaft Position Sensor A Circuit Malfunction
P0336|Crankshaft Position Sensor A Circuit Range/Performance
P0337|Crankshaft Position Sensor A Circuit Low Input
P0338|Crankshaft Position Sensor A Circuit High Input
P0339|Crankshaft Position Sensor A Circuit Intermittent
P0340|Camshaft Position Sensor A Circuit Malfunction (Bank 1 or Single Sensor)
P0341|Camshaft Position Sensor A Circuit Range/Performance (Bank 1 or Single Sensor)
P0342|Camshaft Position Sensor A Circuit Low Input (Bank 1 or Single Sensor)
P0343|Camshaft Position Sensor A Circuit High Input (Bank 1 or Single Sensor)
P0344|Camshaft Position Sensor A Circuit Intermittent (Bank 1 or Single Sensor)
P0345|Camshaft Position Sensor A Circuit (Bank 2)
P0346|Camshaft Position Sensor A Circuit Range/Performance (Bank 2)
P0347|Camshaft Position Sensor A Circuit Low Input (Bank 2)
P0348|Camshaft Position Sensor A Circuit High Input (Bank 2)
P0350|Ignition Coil Primary/Secondary Circuit Malfunction
P0351|Ignition Coil A Primary/Secondary Circuit Malfunction
P0352|Ignition Coil B Primary/Secondary Circuit Malfunction
P0353|Ignition Coil C Primary/Secondary Circuit Malfunction
P0354|Ignition Coil D Primary/Secondary Circuit Malfunction
P0355|Ignition Coil E Primary/Secondary Circuit Malfunction
P0356|Ignition Coil F Primary/Secondary Circuit Malfunction
P0357|Ignition Coil G Primary/Secondary Circuit Malfunction
P0358|Ignition Coil H Primary/Secondary Circuit Malfunction
P0365|Camshaft Position Sensor B Circuit (Bank 1)
P0366|Camshaft Position Sensor B Circuit Range/Performance (Bank 1)
P0367|Camshaft Position Sensor B Circuit Low Input (Bank 1)
P0368|Camshaft Position Sensor B Circuit High Input (Bank 1)
P0369|Camshaft Position Sensor B Circuit Intermittent (Bank 1)
P0370|Timing Reference High Resolution Signal A Malfunction
P0380|Glow Plug/Heater Circuit A Malfunction
P0381|Glow Plug/Heater Indicator Circuit Malfunction
P0385|Crankshaft Position Sensor B Circuit Malfunction
P0390|Camshaft Position Sensor B Circuit (Bank 2)
P0400|Exhaust Gas Recirculation Flow Malfunction
P0401|Exhaust Gas Recirculation Flow Insufficient Detected
P0402|Exhaust Gas Recirculation Flow Excessive Detected
P0403|Exhaust Gas Recirculation Circuit Malfunction
P0404|Exhaust Gas Recirculation Circuit Range/Performance
P0405|Exhaust Gas Recirculation Sensor A Circuit Low
P0406|Exhaust Gas Recirculation Sensor A Circuit High
P0410|Secondary Air Injection System Malfunction
P0411|Secondary Air Injection System Incorrect Flow Detected
P0412|Secondary Air Injection System Switching Valve A Circuit Malfunction
P0418|Secondary Air Injection System Relay A Circuit Malfunction
P0420|Catalyst System Efficiency Below Threshold (Bank 1)
P0421|Warm Up Catalyst Efficiency Below Threshold (Bank 1)
P0422|Main Catalyst Efficiency Below Threshold (Bank 1)
P0430|Catalyst System Efficiency Below Threshold (Bank 2)
P0431|Warm Up Catalyst Efficiency Below Threshold (Bank 2)
P0440|Evaporative Emission Control System Malfunction
P0441|Evaporative Emission Control System Incorrect Purge Flow
P0442|Evaporative Emission Control System Leak Detected (Small Leak)
P0443|Evaporative Emission Control System Purge Control Valve Circuit Malfunction
P0444|Evaporative Emission Control System Purge Control Valve Circuit Open
P0445|Evaporative Emission Control System Purge Control Valve Circuit Shorted
P0446|Evaporative Emission Control System Vent Control Circuit Malfunction
P0447|Evaporative Emission Control System Vent Control Circuit Open
P0448|Evaporative Emission Control System Vent Control Circuit Shorted
P0449|Evaporative Emission Control System Vent Valve/Solenoid Circuit Malfunction
P0450|Evaporative Emission Control System Pressure Sensor Malfunction
P0451|Evaporative Emission Control System Pressure Sensor Range/Performance
P0452|Evaporative Emission Control System Pressure Sensor Low Input
P0453|Evaporative Emission Control System Pressure Sensor High Input
P0454|Evaporative Emission Control System Pressure Sensor Intermittent
P0455|Evaporative Emission Control System Leak Detected (Gross Leak)
P0456|Evaporative Emission Control System Leak Detected (Very Small Leak)
P0457|Evaporative Emission Control System Leak Detected (Fuel Cap Loose/Off)
P0460|Fuel Level Sensor Circuit Malfunction
P0461|Fuel Level Sensor Circuit Range/Performance
P0462|Fuel Level Sensor Circuit Low Input
P0463|Fuel Level Sensor Circuit High Input
P0464|Fuel Level Sensor Circuit Intermittent
P0480|Cooling Fan 1 Control Circuit Malfunction
P0481|Cooling Fan 2 Control Circuit Malfunction
P0482|Cooling Fan 3 Control Circuit Malfunction
P0483|Cooling Fan Rationality Check Malfunction
P0484|Cooling Fan Circuit Over Current
P0485|Cooling Fan Power/Ground Circuit Malfunction
P0500|Vehicle Speed Sensor Malfunction
P0501|Vehicle Speed Sensor Range/Performance
P0502|Vehicle Speed Sensor Circuit Low Input
P0503|Vehicle Speed Sensor Intermittent/Erratic/High
P0505|Idle Control System Malfunction
P0506|Idle Control System RPM Lower Than Expected
P0507|Idle Control System RPM Higher Than Expected
P0508|Idle Air Control System Circuit Low
P0509|Idle Air Control System Circuit High
P0510|Closed Throttle Position Switch Malfunction
P0520|Engine Oil Pressure Sensor/Switch Circuit Malfunction
P0521|Engine Oil Pressure Sensor/Switch Circuit Range/Performance
P0522|Engine Oil Pressure Sensor/Switch Circuit Low Voltage
P0523|Engine Oil Pressure Sensor/Switch Circuit High Voltage
P0524|Engine Oil Pressure Too Low
P0530|A/C Refrigerant Pressure Sensor Circuit Malfunction
P0531|A/C Refrigerant Pressure Sensor Circuit Range/Performance
P0532|A/C Refrigerant Pressure Sensor Circuit Low Input
P0533|A/C Refrigerant Pressure Sensor Circuit High Input
P0550|Power Steering Pressure Sensor Circuit Malfunction
P0560|System Voltage Malfunction
P0561|System Voltage Unstable
P0562|System Voltage Low
P0563|System Voltage High
P0565|Cruise Control On Signal Malfunction
P0571|Cruise Control/Brake Switch A Circuit Malfunction
P0600|Serial Communication Link Malfunction
P0601|Internal Control Module Memory Check Sum Error
P0602|Control Module Programming Error
P0603|Internal Control Module Keep Alive Memory (KAM) Error
P0604|Internal Control Module Random Access Memory (RAM) Error
P0605|Internal Control Module Read Only Memory (ROM) Error
P0606|Control Module Processor Fault
P0607|Control Module Performance
P0615|Starter Relay Circuit
P0620|Generator Control Circuit Malfunction
P0621|Generator Lamp L Control Circuit Malfunction
P0622|Generator Field F Control Circuit Malfunction
P0627|Fuel Pump A Control Circuit/Open
P0638|Throttle Actuator Control Range/Performance (Bank 1)
P0641|Sensor Reference Voltage A Circuit/Open
P0645|A/C Clutch Relay Control Circuit
P0650|Malfunction Indicator Lamp (MIL) Control Circuit Malfunction
P0651|Sensor Reference Voltage B Circuit/Open
P0700|Transmission Control System Malfunction (MIL Request)
P0701|Transmission Control System Range/Performance
P0702|Transmission Control System Electrical
P0703|Torque Converter/Brake Switch B Circuit Malfunction
P0705|Transmission Range Sensor Circuit Malfunction (PRNDL Input)
P0706|Transmission Range Sensor Circuit Range/Performance
P0707|Transmission Range Sensor Circuit Low Input
P0708|Transmission Range Sensor Circuit High Input
P0710|Transmission Fluid Temperature Sensor Circuit Malfunction
P0711|Transmission Fluid Temperature Sensor Circuit Range/Performance
P0712|Transmission Fluid Temperature Sensor Circuit Low Input
P0713|Transmission Fluid Temperature Sensor Circuit High Input
P0715|Input/Turbine Speed Sensor Circuit Malfunction
P0716|Input/Turbine Speed Sensor Circuit Range/Performance
P0717|Input/Turbine Speed Sensor Circuit No Signal
P0720|Output Speed Sensor Circuit Malfunction
P0721|Output Speed Sensor Circuit Range/Performance
P0722|Output Speed Sensor Circuit No Signal
P0725|Engine Speed Input Circuit Malfunction
P0730|Incorrect Gear Ratio
P0731|Gear 1 Incorrect Ratio
P0732|Gear 2 Incorrect Ratio
P0733|Gear 3 Incorrect Ratio
P0734|Gear 4 Incorrect Ratio
P0735|Gear 5 Incorrect Ratio
P0740|Torque Converter Clutch Circuit Malfunction
P0741|Torque Converter Clutch Circuit Performance or Stuck Off
P0742|Torque Converter Clutch Circuit Stuck On
P0743|Torque Converter Clutch Circuit Electrical
P0744|Torque Converter Clutch Circuit Intermittent
P0745|Pressure Control Solenoid Malfunction
P0746|Pressure Control Solenoid Performance or Stuck Off
P0747|Pressure Control Solenoid Stuck On
P0748|Pressure Control Solenoid Electrical
P0750|Shift Solenoid A Malfunction
P0751|Shift Solenoid A Performance or Stuck Off
P0752|Shift Solenoid A Stuck On
P0753|Shift Solenoid A Electrical
P0755|Shift Solenoid B Malfunction
P0756|Shift Solenoid B Performance or Stuck Off
P0757|Shift Solenoid B Stuck On
P0758|Shift Solenoid B Electrical
P0760|Shift Solenoid C Malfunction
P0765|Shift Solenoid D Malfunction
P0770|Shift Solenoid E Malfunction
P0780|Shift Malfunction
P0840|Transmission Fluid Pressure Sensor/Switch A Circuit
P0841|Transmission Fluid Pressure Sensor/Switch A Circuit Range/Performance
P0850|Park/Neutral Switch Input Circuit
P0868|Transmission Fluid Pressure Low
P0882|TCM Power Input Signal Low
P0883|TCM Power Input Signal High
P2004|Intake Manifold Runner Control Stuck Open (Bank 1)
P2005|Intake Manifold Runner Control Stuck Open (Bank 2)
P2006|Intake Manifold Runner Control Stuck Closed (Bank 1)
P2007|Intake Manifold Runner Control Stuck Closed (Bank 2)
P2008|Intake Manifold Runner Control Circuit/Open (Bank 1)
P2096|Post Catalyst Fuel Trim System Too Lean (Bank 1)
P2097|Post Catalyst Fuel Trim System Too Rich (Bank 1)
P2098|Post Catalyst Fuel Trim System Too Lean (Bank 2)
P2099|Post Catalyst Fuel Trim System Too Rich (Bank 2)
P2100|Throttle Actuator Control Motor Circuit/Open
P2101|Throttle Actuator Control Motor Circuit Range/Performance
P2102|Throttle Actuator Control Motor Circuit Low
P2103|Throttle Actuator Control Motor Circuit High
P2111|Throttle Actuator Control System - Stuck Open
P2112|Throttle Actuator Control System - Stuck Closed
P2119|Throttle Actuator Control Throttle Body Range/Performance
P2122|Throttle/Pedal Position Sensor/Switch D Circuit Low Input
P2123|Throttle/Pedal Position Sensor/Switch D Circuit High Input
P2127|Throttle/Pedal Position Sensor/Switch E Circuit Low Input
P2128|Throttle/Pedal Position Sensor/Switch E Circuit High Input
P2135|Throttle/Pedal Position Sensor/Switch A/B Voltage Correlation
P2138|Throttle/Pedal Position Sensor/Switch D/E Voltage Correlation
P2177|System Too Lean Off Idle (Bank 1)
P2178|System Too Rich Off Idle (Bank 1)
P2179|System Too Lean Off Idle (Bank 2)
P2180|System Too Rich Off Idle (Bank 2)
P2187|System Too Lean at Idle (Bank 1)
P2188|System Too Rich at Idle (Bank 1)
P2189|System Too Lean at Idle (Bank 2)
P2190|System Too Rich at Idle (Bank 2)
P2195|O2 Sensor Signal Stuck Lean (Bank 1 Sensor 1)
P2196|O2 Sensor Signal Stuck Rich (Bank 1 Sensor 1)
P2197|O2 Sensor Signal Stuck Lean (Bank 2 Sensor 1)
P2198|O2 Sensor Signal Stuck Rich (Bank 2 Sensor 1)
P2227|Barometric Pressure Circuit Range/Performance
P2228|Barometric Pressure Circuit Low
P2229|Barometric Pressure Circuit High
P2237|O2 Sensor Positive Current Control Circuit/Open (Bank 1 Sensor 1)
P2238|O2 Sensor Positive Current Control Circuit Low (Bank 1 Sensor 1)
P2239|O2 Sensor Positive Current Control Circuit High (Bank 1 Sensor 1)
P2243|O2 Sensor Reference Voltage Circuit/Open (Bank 1 Sensor 1)
P2251|O2 Sensor Negative Current Control Circuit/Open (Bank 1 Sensor 1)
P2261|Turbocharger/Supercharger Bypass Valve - Mechanical
P2262|Turbocharger/Supercharger Boost Pressure Not Detected - Mechanical
P2263|Turbocharger/Supercharger Boost System Performance
P2270|O2 Sensor Signal Stuck Lean (Bank 1 Sensor 2)
P2271|O2 Sensor Signal Stuck Rich (Bank 1 Sensor 2)
P2272|O2 Sensor Signal Stuck Lean (Bank 2 Sensor 2)
P2273|O2 Sensor Signal Stuck Rich (Bank 2 Sensor 2)
P2279|Intake Air System Leak
P2293|Fuel Pressure Regulator 2 Performance
P2300|Ignition Coil A Primary Control Circuit Low
P2301|Ignition Coil A Primary Control Circuit High
P2302|Ignition Coil A Secondary Circuit
P2303|Ignition Coil B Primary Control Circuit Low
P2304|Ignition Coil B Primary Control Circuit High
P2306|Ignition Coil C Primary Control Circuit Low
P2307|Ignition Coil C Primary Control Circuit High
P2309|Ignition Coil D Primary Control Circuit Low
P2310|Ignition Coil D Primary Control Circuit High
P2400|Evaporative Emission System Leak Detection Pump Control Circuit/Open
P2401|Evaporative Emission System Leak Detection Pump Control Circuit Low
P2402|Evaporative Emission System Leak Detection Pump Control Circuit High
P2419|Evaporative Emission System Switching Valve Control Circuit Low
P2440|Secondary Air Injection System Switching Valve Stuck Open (Bank 1)
P2563|Turbocharger Boost Control Position Sensor A Circuit Range/Performance
P2610|ECM/PCM Internal Engine Off Timer Performance
P2626|O2 Sensor Pumping Current Trim Circuit/Open (Bank 1 Sensor 1)
U0001|High Speed CAN Communication Bus
U0073|Control Module Communication Bus A Off
U0100|Lost Communication With ECM/PCM A
U0101|Lost Communication With TCM
U0102|Lost Communication With Transfer Case Control Module
U0104|Lost Communication With Cruise Control Module
U0107|Lost Communication With Throttle Actuator Control Module
U0121|Lost Communication With Anti-Lock Brake System (ABS) Control Module
U0122|Lost Communication With Vehicle Dynamics Control Module
U0126|Lost Communication With Steering Angle Sensor Module
U0131|Lost Communication With Power Steering Control Module
U0140|Lost Communication With Body Control Module
U0151|Lost Communication With Restraints Control Module
U0155|Lost Communication With Instrument Panel Cluster (IPC) Control Module
U0164|Lost Communication With HVAC Control Module
U0401|Invalid Data Received From ECM/PCM A
U0402|Invalid Data Received From TCM
U0415|Invalid Data Received From Anti-Lock Brake System (ABS) Control Module
"""

    init {
        for (line in TABLE.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val i = t.indexOf('|')
            if (i > 0) generic[t.substring(0, i)] = t.substring(i + 1)
        }
    }
}
