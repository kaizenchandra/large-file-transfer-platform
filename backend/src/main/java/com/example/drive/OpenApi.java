package com.example.drive;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.*;import java.lang.reflect.*;

/** Generated from registered handlers, keeping the wire-route inventory tied to running code. */
@RestController
class OpenApi {
 final RequestMappingHandlerMapping mappings;OpenApi(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings){this.mappings=mappings;}
 @GetMapping("/api/v1/openapi.json") Object document(){
  var paths=new TreeMap<String,Object>();var schemas=new TreeMap<String,Object>();
  mappings.getHandlerMethods().forEach((mapping,handler)->{
   for(String route:mapping.getPatternValues())if(route.startsWith("/api/v1/")&&!route.endsWith("openapi.json")){
    var path=(Map<String,Object>)paths.computeIfAbsent(route,k->new TreeMap<String,Object>());
    for(var verb:mapping.getMethodsCondition().getMethods()){
     var operation=new LinkedHashMap<String,Object>();operation.put("operationId",handler.getBeanType().getSimpleName()+"_"+handler.getMethod().getName()+"_"+verb+"_"+Integer.toUnsignedString(route.hashCode()));
     operation.put("security",route.startsWith("/api/v1/public/")?List.of():List.of(Map.of("oidcBearer",List.of()),Map.of("localBasic",List.of())));
     var parameters=new ArrayList<Object>();
     for(var parameter:handler.getMethod().getParameters()){
      var pv=parameter.getAnnotation(PathVariable.class);var qp=parameter.getAnnotation(RequestParam.class);var hd=parameter.getAnnotation(RequestHeader.class);var body=parameter.getAnnotation(RequestBody.class);
      if(body!=null)operation.put("requestBody",Map.of("required",body.required(),"content",Map.of("application/json",Map.of("schema",schema(parameter.getType(),schemas)))));
      if(pv!=null||qp!=null||hd!=null){String name=pv!=null?pv.value():qp!=null?qp.value():hd.value();if(name.isEmpty())name=parameter.getName();parameters.add(Map.of("name",name,"in",pv!=null?"path":qp!=null?"query":"header","required",pv!=null||(qp!=null?qp.required()&&qp.defaultValue().equals(org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE):hd.required()),"schema",schema(parameter.getType(),schemas)));}
     }
     if(!parameters.isEmpty())operation.put("parameters",parameters);
     var responses=new LinkedHashMap<String,Object>();String success=route.endsWith("/complete")||route.endsWith("/reconciliation/run")?"202":"200";
     responses.put(success,Map.of("description",success.equals("202")?"Durably accepted; poll the resource. Acceptance is not verification or publication.":"Operation succeeded; response shape is endpoint-specific.","content",Map.of("application/json",Map.of("schema",Map.of("type","object","additionalProperties",true)))));
     for(String code:List.of("400","401","403","404","409","410","412","413","422","429","503"))responses.put(code,Map.of("description","Validation, authorization, state, policy, rate or dependency failure. See Problem Details.","content",Map.of("application/problem+json",Map.of("schema",Map.of("$ref","#/components/schemas/Problem")))));
     operation.put("responses",responses);operation.put("description","Tenant and object authorization are enforced by the handler. Responses are bounded; large bytes transfer through signed storage credentials. Idempotency-Key, when accepted, is scoped to the authenticated owner and request. Signed URLs are bearer credentials and must not be logged.");path.put(verb.name().toLowerCase(Locale.ROOT),operation);
    }
   }
  });
  schemas.put("Problem",Map.of("type","object","properties",Map.of("type",Map.of("type","string"),"title",Map.of("type","string"),"status",Map.of("type","integer"),"detail",Map.of("type","string"),"instance",Map.of("type","string"))));
  return Map.of("openapi","3.1.1","info",Map.of("title","Harbor API","version","1.0.0","description","Live route and request-schema inventory. Generic response schemas remain open; this document does not replace endpoint-specific response DTO contracts."),"paths",paths,"components",Map.of("schemas",schemas,"securitySchemes",Map.of("oidcBearer",Map.of("type","http","scheme","bearer","bearerFormat","JWT"),"localBasic",Map.of("type","http","scheme","basic","description","Development profile only"))));
 }
 Map<String,Object> schema(Class<?> type,Map<String,Object> schemas){
  if(type.isRecord()){
   String name=type.getDeclaringClass()==null?type.getSimpleName():type.getDeclaringClass().getSimpleName()+type.getSimpleName();
   if(!schemas.containsKey(name)){var properties=new LinkedHashMap<String,Object>();var required=new ArrayList<String>();schemas.put(name,Map.of());for(var field:type.getRecordComponents()){properties.put(field.getName(),schema(field.getType(),schemas));if(field.getType().isPrimitive())required.add(field.getName());}var result=new LinkedHashMap<String,Object>();result.put("type","object");result.put("properties",properties);if(!required.isEmpty())result.put("required",required);schemas.put(name,result);}
   return Map.of("$ref","#/components/schemas/"+name);
  }
  if(type.isArray())return Map.of("type","array","items",schema(type.getComponentType(),schemas));
  if(java.util.Collection.class.isAssignableFrom(type))return Map.of("type","array","items",Map.of("type","object"));
  if(type==boolean.class||type==Boolean.class)return Map.of("type","boolean");if(type==int.class||type==long.class||Number.class.isAssignableFrom(type))return Map.of("type","integer");if(type==UUID.class)return Map.of("type","string","format","uuid");return Map.of("type","string");
 }
}
