# concord-runtime-model

Common interfaces for Concord runtimes. Declares top-level entities such as "process configuration", "profiles", etc.
Used by the runtime v2 project loader and server code that works with parsed process definitions.

The `concord-v2` runtime implements concord-runtime-model interfaces in the
`com.walmartlabs.concord.runtime.v2.wrapper` package.

Future runtimes can implement those interfaces directly in their respective model classes.
