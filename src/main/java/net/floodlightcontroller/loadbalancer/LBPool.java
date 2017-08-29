/**
 *    Copyright 2013, Big Switch Networks, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.loadbalancer;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import org.projectfloodlight.openflow.types.U64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import net.floodlightcontroller.loadbalancer.LoadBalancer.IPClient;

/**
 * Data structure for Load Balancer based on
 * Quantum proposal http://wiki.openstack.org/LBaaS/CoreResourceModel/proposal 
 * 
 * @author KC Wang
 */


@JsonSerialize(using=LBPoolSerializer.class)
public class LBPool {
	protected static Logger log = LoggerFactory.getLogger(LBPool.class);
	protected String id;
	protected String name;
	protected String tenantId;
	protected String netId;
	protected short lbMethod;
	protected byte protocol;
	protected ArrayList<String> members;
	protected ArrayList<String> monitors;
	protected ArrayList<String> test_WRR; // !!!
	protected ArrayList<String> test_STATS; // !!!
	protected ArrayList<Long> test_Min_BW; // !!!
	protected ArrayList<Long> test_Max_BW; // !!!
	protected ArrayList<String> prevPicked;
	protected short adminState;
	protected short status;
	protected final static short ROUND_ROBIN = 1;
	protected final static short STATISTICS = 2;
	protected final static short WEIGHTED_RR = 3;

	protected String vipId;

	protected int previousMemberIndex;

	protected LBStats poolStats;

	public LBPool() {
		id = String.valueOf((int) (Math.random()*10000));
		name = null;
		tenantId = null;
		netId = null;
		lbMethod = 0;
		protocol = 0;
		members = new ArrayList<String>();
		prevPicked = new ArrayList<String>();
		monitors = new ArrayList<String>();
		test_WRR = new ArrayList<String>(); // !!
		test_STATS = new ArrayList<String>(); // !!
		test_Min_BW = new ArrayList<Long>(); // !!
		test_Max_BW = new ArrayList<Long>(); // !!
		adminState = 0;
		status = 0;
		previousMemberIndex = -1;

		poolStats = new LBStats();
	}


	public void setPoolStatistics(ArrayList<Long> bytesIn,ArrayList<Long> bytesOut,int activeFlows){
		if(!bytesIn.isEmpty() && !bytesOut.isEmpty()){
			long sumIn = 0;
			long sumOut = 0; 

			for(Long bytes: bytesIn){
				sumIn += bytes;
			}
			poolStats.bytesIn = sumIn; 

			for(Long bytes: bytesOut){
				sumOut += bytes;
			}
			poolStats.bytesOut = sumOut;
			poolStats.activeFlows = activeFlows;
		}
	}

	public String pickMember(IPClient client, HashMap<String,U64> membersBandwidth,HashMap<String,Short> membersWeight,HashMap<String, Short>  memberStatus) {
		if(members.size() > 0){
			if (lbMethod == STATISTICS && !membersBandwidth.isEmpty() && membersBandwidth.values() !=null) {
				ArrayList<String> poolMembersId = new ArrayList<String>();				
				// Get the members that belong to this pool and the statistics for them
				for(String memberId: membersBandwidth.keySet()){
					for(int i=0;i<members.size();i++){
						if(LoadBalancer.isMonitoringEnabled && !monitors.isEmpty() && !memberStatus.isEmpty()){  // if health monitors active
							if(members.get(i).equals(memberId) && memberStatus.get(memberId) == 1){
								poolMembersId.add(memberId);
							}
						} else { // no health monitors active
							if(members.get(i).equals(memberId)){
								poolMembersId.add(memberId);
							}
						}
					}
				}
				// return the member which has the minimum bandwidth usage, out of this pool members
				if(!poolMembersId.isEmpty()){
					ArrayList<U64> bandwidthValues = new ArrayList<U64>();	
					ArrayList<String> membersWithMin = new ArrayList<String>();
					Collections.sort(poolMembersId);

					for(int j=0;j<poolMembersId.size();j++){
						bandwidthValues.add(membersBandwidth.get(poolMembersId.get(j)));
					}
					U64 minBW = Collections.min(bandwidthValues);
					String memberToPick = poolMembersId.get(bandwidthValues.indexOf(minBW));

					for(Integer i=0;i<bandwidthValues.size();i++){
						if(bandwidthValues.get(i).equals(minBW)){
							membersWithMin.add(poolMembersId.get(i));
						}
					}
					//log.info("LIST OF Members: " + members);
					log.info("LIST OF IDS: " + poolMembersId);
					log.info("LIST OF MINS: " + membersWithMin);

					// size of the prev list is half of the number of available members
					int sizeOfPrevPicked = bandwidthValues.size()/2;
					
					//log.info("PREV PICKED B4: " + prevPicked);

					// Remove previously picked members from being eligible for being picked now
					for (Iterator<String> it = membersWithMin.iterator(); it.hasNext();){
						String memberMin = it.next();
						if(prevPicked.contains(memberMin)){
							it.remove();
						}
					}
					// Keep the previously picked list to a size based on the members of the pool
					if(prevPicked.size() > sizeOfPrevPicked){					    
						prevPicked.remove(prevPicked.size()-1);
					}
					
					// If there is only one member with min BW value and membersWithMin is empty
					if(membersWithMin.isEmpty()){
						memberToPick = prevPicked.get(prevPicked.size()-1); // means that the min member has been prevs picked
					}else
						memberToPick = membersWithMin.get(0);

					prevPicked.add(0, memberToPick); //set the first memberId of prevPicked to be the last member picked
					//log.info("PREV PICKED AFTER: " + prevPicked);

					test_STATS.add(memberToPick);
					test_Min_BW.add((Collections.min(bandwidthValues)).getValue());
					test_Max_BW.add((Collections.max(bandwidthValues)).getValue());
					log.info("STAAT: " + test_STATS);
					log.info("MIN: " + test_Min_BW);
					log.info("MAX: " + test_Max_BW);

					return memberToPick;
				}
				return null;
			} else if(lbMethod == WEIGHTED_RR && !membersWeight.isEmpty()){

				HashMap<String, Short> activeMembers = new HashMap<String, Short>();

				if(LoadBalancer.isMonitoringEnabled && !monitors.isEmpty() && !memberStatus.isEmpty()){  // if health monitors active
					for(String memberId: membersWeight.keySet()){
						if(memberStatus.get(memberId) == 1){
							activeMembers.put(memberId, membersWeight.get(memberId)); 
						}
					}
					return weightsToMember(activeMembers); // only members with status = 1

				} else
					return weightsToMember(membersWeight); // all members in membersWeight are considered	
			}else {
				if(LoadBalancer.isMonitoringEnabled && !monitors.isEmpty() && !memberStatus.isEmpty()){  // if health monitors active
					for(int i=0;i<members.size();){
						previousMemberIndex = (previousMemberIndex + 1) % members.size();	
						if(memberStatus.get(members.get(previousMemberIndex)) == 1)
							return members.get((previousMemberIndex));     		
					}
					return null;
				} else{
					// simple round robin
					previousMemberIndex = (previousMemberIndex + 1) % members.size();
					return members.get(previousMemberIndex);
				}
			}
		}
		return null;
	}

	/**
	 * helper function to pick a member
	 * @param weights - hashmap with memberId and weight of the member
	 * @return member picked by Weighted Round Robin
	 */
	private String weightsToMember(HashMap<String, Short> weights){
		Random randomNumb = new Random();
		short totalWeight = 0;

		for(Short weight: weights.values()){
			totalWeight += weight;
		}

		int rand = randomNumb.nextInt(totalWeight);
		short val = 0;
		for(String memberId: weights.keySet()){
			val += weights.get(memberId);
			if(val > rand){
				log.debug("Member {} picked using WRR",memberId);
				//				test_WRR.add(memberId);
				//				log.info("1: "+Collections.frequency(test_WRR, "1"));
				//				log.info("2: "+Collections.frequency(test_WRR, "2"));
				//				log.info("3: "+Collections.frequency(test_WRR, "3"));
				//				log.info("4: "+Collections.frequency(test_WRR, "4"));
				//				log.info("5: "+Collections.frequency(test_WRR, "5"));
				//				log.info("6: "+Collections.frequency(test_WRR, "6"));
				//				log.info("7: "+Collections.frequency(test_WRR, "7"));
				//				log.info("8: "+Collections.frequency(test_WRR, "8"));
				return memberId;
			}
		}
		return null;		
	}
}
