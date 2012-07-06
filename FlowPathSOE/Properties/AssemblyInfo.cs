using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using ESRI.ArcGIS.SOESupport;

// General Information about an assembly is controlled through the following 
// set of attributes. Change these attribute values to modify the information
// associated with an assembly.
[assembly: AssemblyTitle("FlowPathSOE")]
[assembly: AssemblyDescription("")]
[assembly: AssemblyConfiguration("")]
[assembly: AssemblyCompany("National Geographic Society")]
[assembly: AssemblyProduct("FlowPathSOE")]
[assembly: AssemblyCopyright("Copyright © 2012 National Geographic Society")]
[assembly: AssemblyTrademark("")]
[assembly: AssemblyCulture("")]

// Setting ComVisible to false makes the types in this assembly not visible 
// to COM components.  If you need to access a type in this assembly from 
// COM, set the ComVisible attribute to true on that type.
[assembly: ComVisible(false)]

// The following GUID is for the ID of the typelib if this project is exposed to COM
[assembly: Guid("669690d1-88ca-41e2-8807-586a016a64ba")]

// Version information for an assembly consists of the following four values:
//
//      Major Version
//      Minor Version 
//      Build Number
//      Revision
//
// You can specify all the values or you can default the Build and Revision Numbers 
// by using the '*' as shown below:
// [assembly: AssemblyVersion("1.0.*")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

[assembly: AddInPackage("FlowPathSOE", "bc41b392-0e03-43f2-ab77-03e4f137c030",
    Author = "Eric Russell",
    Company = "National Geographic Society",
    Date = "7/2/2012 10:48:48 AM",
    Description = "Computes flow path downhill from pour point",
    TargetProduct = "Server",
    TargetVersion = "10.1",
    Version = "1.0")]
