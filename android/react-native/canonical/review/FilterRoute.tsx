import {useEffect,useMemo,useState} from 'react';
import {useRoute} from '@react-navigation/native';
import {useAndroidRouteRemoval} from './android-route-removal';
import {FilterScreen} from './FilterScreens';
import {FilterDetailOwnerContext} from './filter-route';
import {LiveRenderBoundary,useReview} from './ReviewContext';

export function FilterRoute(){
  const {app,live}=useReview();const route=useRoute();
  const [initialID]=useState(live?.session?.filterID);
  const id=(route.params as {id?:string}|undefined)?.id??initialID;
  const owner=useMemo(()=>({id}),[id]);
  useAndroidRouteRemoval();
  useEffect(()=>{
    if(!app||!id)return;
    return()=>{void app.command({type:'filter.close',id}).catch(()=>{});};
  },[app,id]);
  return <FilterDetailOwnerContext.Provider value={owner}><LiveRenderBoundary component={FilterScreen} retainBody directScrollRoot/></FilterDetailOwnerContext.Provider>;
}
