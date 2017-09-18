include "console.iol"

type TmpType: void { .id?: string }

interface ThermostatInterface {
    OneWay: test( string )
    RequestResponse: getTmp( TmpType )( undefined )
}

outputPort Server {
    Location: "socket://localhost:8000"
    Protocol: sodep
    Interfaces: ThermostatInterface
}

outputPort Broker {
    Location: "socket://localhost:1883"
    Protocol: mqtt {
        .debug = true;
        .osc.getTmp << {
            .format = "xml",
            .alias = "%!{id}/getTemperature",
            .QoS = 2
        };
        .osc.test << {
            .format = "xml",
            .alias = "test/getTemperature",
            .QoS = 2
        }
    }
    Interfaces: ThermostatInterface
}

main
{
    {
        test@Server( "This is a test" );
        println@Console( "Test done" )()
    }
    ;
    {
        getTmp@Server( { .id = "42" } )( varA );
        println@Console( "getTmp done: " + varA )()
    }
}
